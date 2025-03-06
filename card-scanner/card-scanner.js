import { loadOpenCV } from "./opencv_js.js";

class CardScanner extends HTMLElement {
  constructor() {
    super();

    this.cardWidth = 430;
    this.cardHeight = 600;

    this.config = {
      audio: false,
      video: {
        width: 620,
        height: 620,
        facingMode: { exact: "environment" },
      },
    };

    this.hashThreshold = 15;
    this.resultsLimit = 20;
    this.certaintyThreshold = 70;
    this.cardResults = [];
  }

  someHashSegmentsMatch(arr1, arr2) {
    for (let i = 0; i < arr1.length; i++) {
      if (arr1[i] === arr2[i]) {
        return true;
      }
    }
    return false;
  }

  hammingDistance(n) {
    let count = 0n;
    while (n) {
      count += n & 1n;
      n >>= 1n;
    }
    return count;
  }

  startVideo() {
    if (!this.video) {
      this.video = this.querySelector("video");
      this.overlay = this.querySelector("svg");
    }
    if (!this.video.paused) return;
    navigator.mediaDevices
      .getUserMedia(this.config)
      .then((mediaStream) => {
        this.mediaStream = mediaStream;
        this.video.srcObject = this.mediaStream;
        this.processFrame();
      })
      .catch((error) => {
        // TODO: Handle error if camera isn't available
      });
  }

  stopVideo() {
    this.mediaStream?.getTracks().forEach(function (track) {
      track.stop();
    });
    if (this.video) this.video.srcObject = null;
    this.cardResults = [];
    if (this.overlay) this.overlay.style.display = "none";
  }

  getPHashSegments(hash) {
    return [
      Number((hash >> BigInt(56)) & BigInt(0xff)),
      Number((hash >> BigInt(48)) & BigInt(0xff)),
      Number((hash >> BigInt(40)) & BigInt(0xff)),
      Number((hash >> BigInt(32)) & BigInt(0xff)),
      Number((hash >> BigInt(24)) & BigInt(0xff)),
      Number((hash >> BigInt(16)) & BigInt(0xff)),
      Number((hash >> BigInt(8)) & BigInt(0xff)),
      Number((hash >> BigInt(0)) & BigInt(0xff)),
    ];
  }

  getPHash(imageMat) {
    const phash = new this.cv.img_hash_PHash();
    const hashMat = new this.cv.Mat();
    phash.compute(imageMat, hashMat);
    const result = hashMat.data.reduce(
      (acc, byte) => (acc << BigInt(8)) | BigInt(byte),
      BigInt(0),
    );
    hashMat.delete();
    return result;
  }

  detectCardPresence(src) {
    const gray = new this.cv.Mat();
    this.cv.cvtColor(src, gray, this.cv.COLOR_RGBA2GRAY);
    this.cv.GaussianBlur(gray, gray, new this.cv.Size(19, 19), 0, 0);

    const edges = new this.cv.Mat();
    this.cv.Canny(gray, edges, 60, 90);

    const lines = new this.cv.Mat();
    this.cv.HoughLinesP(edges, lines, 1, Math.PI / 180, 50, 100, 200);

    let xMin = gray.cols / 2 + this.cardWidth / 2,
      xMax = 5,
      yMin = gray.rows / 2 + this.cardHeight / 2,
      yMax = 5;

    for (let i = 0; i < lines.rows; ++i) {
      const [x1, y1, x2, y2] = lines.data32S.slice(i * 4, i * 4 + 4);
      xMin = Math.min(xMin, x1, x2);
      xMax = Math.max(xMax, x1, x2);
      yMin = Math.min(yMin, y1, y2);
      yMax = Math.max(yMax, y1, y2);
    }

    const detectedWidth = xMax - xMin;
    const detectedHeight = yMax - yMin;

    let result = null;

    if (
      detectedWidth > 0 &&
      detectedHeight > 0 &&
      detectedWidth < this.cardWidth &&
      detectedHeight < this.cardHeight &&
      (detectedWidth / this.cardWidth > 0.95 ||
        detectedHeight / this.cardHeight > 0.95)
    ) {
      // NOTE: Uncomment for debugging the card bounding box detection
      //
      // this.cv.rectangle(
      //   src,
      //   new this.cv.Point(xMin, yMin),
      //   new this.cv.Point(xMax, yMax),
      //   new this.cv.Scalar(0, 255, 0, 255),
      //   2,
      // );

      // this.cv.imshow("preview", src);

      result = {
        x: xMin,
        y: yMin,
        width: detectedWidth,
        height: detectedHeight,
      };
    }
    gray.delete();
    edges.delete();
    lines.delete();
    return result;
  }

  showScanResults(result) {
    this.querySelector("#scan_results").textContent = result;
    this.querySelector("#scan_results").parentElement.style.display = "block";
  }

  processFrame() {
    requestAnimationFrame(() => {
      this.overlay.style.opacity = "";
      if (!this.video.paused) {
        this.overlay.style.display = "block";
        this.createCanvas();
        const canvasCtx = this.canvas.getContext("2d");
        canvasCtx.drawImage(
          this.video,
          0,
          0,
          this.video.videoWidth,
          this.video.videoHeight,
        );
        const imageData = canvasCtx.getImageData(
          0,
          0,
          this.video.videoWidth,
          this.video.videoHeight,
        );
        const imageMat = new this.cv.matFromImageData(imageData);
        if (this.config.video.height != this.video.videoHeight) {
          const scale = this.config.video.height / this.video.videoHeight;
          this.cv.resize(
            imageMat,
            imageMat,
            new this.cv.Size(
              Math.round(imageMat.cols * scale),
              Math.round(imageMat.rows * scale),
            ),
            0,
            0,
            this.cv.INTER_CUBIC,
          );
        }

        const detectedCard = this.detectCardPresence(imageMat);
        if (detectedCard) {
          this.overlay.style.opacity = 1;
          const resizedCard = imageMat.roi(
            new this.cv.Rect(
              Math.round(imageMat.cols / 2 - this.cardWidth / 2),
              Math.round(imageMat.rows / 2 - this.cardHeight / 2),
              this.cardWidth,
              this.cardHeight,
            ),
          );
          const icon = resizedCard.roi(new this.cv.Rect(96, 64, 300, 215));
          resizedCard.delete();
          const hash = this.getPHash(icon);
          const hashSegments = this.getPHashSegments(hash);
          const result = this.queryDB(hash, hashSegments);
          if (result.length) {
            if (this.cardResults.length < this.resultsLimit) {
              this.cardResults = this.cardResults.concat(result);
            }
            if (this.cardResults.length >= this.resultsLimit) {
              const finalResult = this.cardResults.reduce(function (
                accl,
                entry,
              ) {
                const number = entry[1];
                if (number in accl) {
                  accl[number] = accl[number] + 1;
                } else {
                  accl[number] = 1;
                }
                return accl;
              }, {});
              const mostAccurateMatch = Object.entries(finalResult).sort(
                function (a, b) {
                  return b[1] - a[1];
                },
              )[0];
              if (
                (mostAccurateMatch[1] / this.resultsLimit) * 100 <
                this.certaintyThreshold
              ) {
                this.cardResults = [];
              } else {
                this.showScanResults(mostAccurateMatch[0]);
              }
              if (!this.resetting) {
                this.resetting = setTimeout(() => {
                  this.cardResults = [];
                  this.resetting = null;
                }, 1000);
              }
            }
          }
          icon.delete();
        }

        imageMat.delete();
      }
      this.processFrame();
    });
  }

  createCanvas() {
    if (this.canvas) return;
    this.canvas = document.createElement("canvas");
    this.canvas.width = this.video.videoWidth;
    this.canvas.height = this.video.videoHeight;
  }

  async initDB() {
    const response = await fetch("hash_db.json");
    let json = await response.json();
    json = json.map((entry) => {
      const hash = BigInt("" + entry[0]);
      const number = entry[1];
      const segments = this.getPHashSegments(hash);
      return [hash, segments, number];
    });
    return json;
  }

  queryDB(hash, hashSegments) {
    return this.hashDB
      .filter((entry) => {
        const segments = entry[1];
        return this.someHashSegmentsMatch(segments, hashSegments);
      })
      .reduce((accl, entry) => {
        const h = entry[0];
        const distance = this.hammingDistance(h ^ hash);
        if (distance <= this.hashThreshold) {
          const numberExists = accl.some(function (e) {
            return e[1] == entry[2];
          });
          if (!numberExists) {
            accl.push([distance, entry[2]]);
          } else {
            const worseMatch = accl.find(function (e) {
              return e[0] > distance;
            });
            if (worseMatch) {
              accl = accl.filter(function (e) {
                return e[0] != worseMatch[0] && e[1] != worseMatch[1];
              });
              accl.push([distance, entry[2]]);
            }
          }
        }
        return accl;
      }, []);
  }

  async connectedCallback() {
    this.cv = await loadOpenCV();
    this.hashDB = await this.initDB();

    this.startVideoButton = this.querySelector("button[name=start]");
    this.startVideoHandler = (_) => {
      this.startVideo();
    };
    this.startVideoButton.addEventListener("click", this.startVideoHandler);
    this.stopVideoButton = this.querySelector("button[name=stop]");
    this.stopVideoHandler = (_) => {
      this.stopVideo();
    };
    this.stopVideoButton.addEventListener("click", this.stopVideoHandler);
  }

  disconnectedCallback() {
    this.startVideoButton.removeEventListener("click", this.startVideoHandler);
    this.stopVideoButton.removeEventListener("click", this.stopVideoHandler);
  }
}

customElements.define("card-scanner", CardScanner);
