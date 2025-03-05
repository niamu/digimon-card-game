import { loadOpenCV } from "./opencv_js.js";

class CardScanner extends HTMLElement {
  constructor() {
    super();
    this.config = {
      audio: false,
      video: {
        width: 450,
        height: 620,
        facingMode: { exact: "environment" },
      },
    };

    this.hashThreshold = 14;
    this.resultsLimit = 25;
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
      this.video.width = this.config.video.width;
      this.video.height = this.config.video.height;
      this.overlay = this.querySelector("svg");
      this.createCanvas();
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
    this.mediaStream.getTracks().forEach(function (track) {
      track.stop();
    });
    this.video.srcObject = null;
    this.cardResults = [];
    this.overlay.style.display = "none";
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
    this.cv.GaussianBlur(gray, gray, new this.cv.Size(7, 7), 0, 0);

    let edges = new this.cv.Mat();
    this.cv.Canny(gray, edges, 10, 50);

    let lines = new this.cv.Mat();
    this.cv.HoughLinesP(edges, lines, 1, Math.PI / 180, 80, 100, 15);

    let xMin = src.cols,
      xMax = 0,
      yMin = src.rows,
      yMax = 0;

    for (let i = 0; i < lines.rows; ++i) {
      let [x1, y1, x2, y2] = lines.data32S.slice(i * 4, i * 4 + 4);

      xMin = Math.min(xMin, x1, x2);
      xMax = Math.max(xMax, x1, x2);
      yMin = Math.min(yMin, y1, y2);
      yMax = Math.max(yMax, y1, y2);
    }

    gray.delete();
    edges.delete();
    lines.delete();

    const expectedWidth = 430;
    const expectedHeight = 600;

    let detectedWidth = xMax - xMin;
    let detectedHeight = yMax - yMin;

    if (
      xMin >= 5 &&
      yMin >= 5 &&
      detectedWidth >= expectedWidth - 50 &&
      detectedHeight >= expectedHeight - 50
    ) {
      return { x: xMin, y: yMin, width: detectedWidth, height: detectedHeight };
    }
    return null;
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
        const canvasCtx = this.canvas.getContext("2d");
        canvasCtx.drawImage(
          this.video,
          0,
          0,
          this.config.video.width,
          this.config.video.height,
        );
        const imageData = canvasCtx.getImageData(
          0,
          0,
          this.config.video.width,
          this.config.video.height,
        );
        const imageMat = new this.cv.matFromImageData(imageData);

        if (this.detectCardPresence(imageMat)) {
          const icon = imageMat.roi(
            new this.cv.Rect(96 + 10, 64 + 10, 300, 215),
          );
          const hash = this.getPHash(icon);
          const hashSegments = this.getPHashSegments(hash);
          const result = this.queryDB(hash, hashSegments);
          if (result.length) {
            if (this.cardResults.length < this.resultsLimit) {
              this.cardResults = this.cardResults.concat(result);
              this.overlay.style.opacity = 1;
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
              setTimeout(() => {
                this.cardResults = [];
              }, 2000);
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
    this.canvas.width = this.config.video.width;
    this.canvas.height = this.config.video.height;
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
        if (distance < this.hashThreshold) {
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
