import { loadOpenCV } from "./opencv_js.js";

class CardScanner extends HTMLElement {
  constructor() {
    super();

    this.cardWidth = 430;
    this.cardHeight = 600;

    this.cardMargin = 100;

    this.config = {
      audio: false,
      video: {
        width: 2000,
        height: 2000,
        facingMode: { exact: "environment" },
      },
    };

    this.hashThreshold = 16;
    this.resultsLimit = 60;
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
    if (!this.video.paused) return;

    this.startVideoButton.remove();
    this.createStopButton();

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
    if (this.video.paused) return;

    this.stopVideoButton.remove();
    this.createStartButton();

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
    this.cv.GaussianBlur(gray, gray, new this.cv.Size(15, 15), 0, 0);

    const edges = new this.cv.Mat();
    this.cv.Canny(gray, edges, 10, 30);

    const lines = new this.cv.Mat();
    this.cv.HoughLinesP(
      edges,
      lines,
      1,
      Math.PI / 180,
      150,
      this.cardWidth / 2,
      this.cardWidth / 10,
    );

    const xMin = gray.cols / 2 - this.cardWidth / 2;
    const xMax = gray.cols / 2 + this.cardWidth / 2;
    const yMin = gray.rows / 2 - this.cardHeight / 2;
    const yMax = gray.rows / 2 + this.cardHeight / 2;

    let rectXMin, rectXMax, rectYMin, rectYMax;

    const clamp = (num, min, max) => Math.min(Math.max(num, min), max);

    for (let i = 0; i < lines.rows; ++i) {
      const [x1, y1, x2, y2] = lines.data32S.slice(i * 4, i * 4 + 4);
      rectXMin = clamp(
        Math.min.apply(null, [x1, x2].concat(rectXMin ? rectXMin : [])),
        xMin,
        xMax,
      );
      rectXMax = clamp(
        Math.max.apply(null, [x1, x2].concat(rectXMax ? rectXMax : [])),
        xMin,
        xMax,
      );
      rectYMin = clamp(
        Math.min.apply(null, [y1, y2].concat(rectYMin ? rectYMin : [])),
        yMin,
        yMax,
      );
      rectYMax = clamp(
        Math.max.apply(null, [y1, y2].concat(rectYMax ? rectYMax : [])),
        yMin,
        yMax,
      );
    }

    if (this.debug) {
      this.cv.rectangle(
        src,
        new this.cv.Point(rectXMin, rectYMin),
        new this.cv.Point(rectXMax, rectYMax),
        new this.cv.Scalar(0, 255, 0, 255),
        2,
      );
      this.cv.imshow("preview", src);
    }

    const detectedWidth = rectXMax - rectXMin;
    const detectedHeight = rectYMax - rectYMin;

    let result = null;

    if (
      detectedWidth > 0 &&
      detectedHeight > 0 &&
      detectedWidth <= this.cardWidth &&
      detectedHeight <= this.cardHeight &&
      detectedWidth / this.cardWidth > 0.98 &&
      detectedHeight / this.cardHeight > 0.98
    ) {
      result = {
        x: rectXMin,
        y: rectYMin,
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
    this.scanResults.showModal();
    this.stopVideo();
  }

  processFrame() {
    requestAnimationFrame(() => {
      if (this.overlay && this.overlay.style.opacity)
        this.overlay.style.opacity = "";
      if (this.video.paused || this.cardResults.length >= this.resultsLimit) {
        if (this.getAttribute("playing") != null)
          this.removeAttribute("playing");
        return this.processFrame();
      }
      if (!this.getAttribute("playing")) this.setAttribute("playing", "");
      if (this.overlay && this.overlay.style.display != "block")
        this.overlay.style.display = "block";
      this.createCanvas();
      this.createOverlay();
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
      if (this.cardHeight + this.cardMargin * 2 != this.video.videoHeight) {
        const scale =
          (this.cardHeight + this.cardMargin * 2) / this.video.videoHeight;
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
        if (this.overlay.style.opacity != 1) this.overlay.style.opacity = 1;
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
            let finalResult = this.cardResults.reduce(function (accl, entry) {
              const distance = Number(entry[0]);
              const number = entry[1];
              if (number in accl) {
                accl[number].push(distance);
              } else {
                accl[number] = [distance];
              }
              return accl;
            }, {});
            finalResult = Object.entries(finalResult).reduce(
              (accl, [number, distances]) => {
                accl.push([
                  number,
                  (distances.reduce((a, b) => a + b, 0) / distances.length) *
                    (1 / distances.length),
                ]);
                return accl;
              },
              [],
            );
            const mostAccurateMatch = finalResult.sort(function (a, b) {
              return a[1] - b[1];
            })[0];
            this.showScanResults(mostAccurateMatch[0]);
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
      this.processFrame();
    });
  }

  createStopButton() {
    this.stopVideoButton = this.stopVideoButton
      ? this.stopVideoButton
      : document.createElement("button");
    this.stopVideoButton.type = "button";
    this.stopVideoButton.name = "stop";
    if (!this.stopVideoButton.querySelector("span")) {
      const span = document.createElement("span");
      const text = document.createElement("em");
      text.innerText = "Stop";
      span.appendChild(text);
      this.stopVideoButton.appendChild(span);
    }
    this.stopVideoButton.addEventListener("click", this.stopVideoHandler);
    this.controlsContainer.appendChild(this.stopVideoButton);
  }

  createStartButton() {
    this.startVideoButton = this.startVideoButton
      ? this.startVideoButton
      : document.createElement("button");
    this.startVideoButton.type = "button";
    this.startVideoButton.name = "start";
    if (!this.startVideoButton.querySelector("span")) {
      const span = document.createElement("span");
      const text = document.createElement("em");
      text.innerText = "Start";
      span.appendChild(text);
      this.startVideoButton.appendChild(span);
    }
    this.startVideoButton.addEventListener("click", this.startVideoHandler);
    this.controlsContainer.appendChild(this.startVideoButton);
  }

  createVideo() {
    if (this.video) return;
    this.video = document.createElement("video");
    this.video.setAttribute("autoplay", "");
    this.video.setAttribute("playsinline", "");
    this.video.setAttribute("muted", "");

    this.startVideoHandler = (_) => {
      this.startVideo();
    };
    this.stopVideoHandler = (_) => {
      this.stopVideo();
    };

    this.scanResults.addEventListener("close", this.startVideoHandler);

    this.container.insertBefore(
      this.video,
      this.container.querySelector("form#htmlOnly"),
    );
    this.controlsContainer = document.createElement("div");
    this.controlsContainer.className = "controls";
    this.video.insertAdjacentElement("afterend", this.controlsContainer);
    this.createStartButton();
  }

  createCanvas() {
    if (this.canvas) return;
    this.canvas = document.createElement("canvas");
    this.canvas.width = this.video.videoWidth;
    this.canvas.height = this.video.videoHeight;
  }

  createPreviewCanvas() {
    const canvas = document.createElement("canvas");
    canvas.id = "preview";
    this.insertBefore(canvas, this.querySelector("video"));
  }

  createOverlay() {
    if (this.overlay || this.video.paused) return;
    const videoDimensions = this.video.getBoundingClientRect();
    const scale =
      (this.cardHeight + this.cardMargin * 2) / videoDimensions.height;
    const svgNS = "http://www.w3.org/2000/svg";
    const height = videoDimensions.height * scale;
    const width = videoDimensions.width * scale;
    this.overlay = document.createElementNS(svgNS, "svg");
    this.overlay.setAttribute("viewBox", `0 0 ${width} ${height}`);
    this.overlay.setAttribute("width", width);
    this.overlay.setAttribute("height", height);
    this.overlay.setAttribute("role", "img");
    this.overlay.setAttribute("aria-label", "card outline");

    const rect = document.createElementNS(svgNS, "rect");
    const strokeWidth = 6;
    const outlineHeight = height - this.cardMargin * 2;
    const outlineWidth = outlineHeight * (this.cardWidth / this.cardHeight);
    rect.setAttribute("x", width / 2 - outlineWidth / 2);
    rect.setAttribute("y", height / 2 - outlineHeight / 2);
    rect.setAttribute("rx", 18);
    rect.setAttribute("ry", 18);
    rect.setAttribute("width", outlineWidth);
    rect.setAttribute("height", outlineHeight);
    rect.setAttribute("stroke", "#FFF");
    rect.setAttribute("stroke-width", strokeWidth);
    rect.setAttribute("fill", "none");

    this.overlay.appendChild(rect);

    this.video.insertAdjacentElement("afterend", this.overlay);
  }

  async initDB() {
    const response = await fetch("./js/hash_db.json");
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

  async registerServiceWorker() {
    if ("serviceWorker" in navigator) {
      try {
        const registration = await navigator.serviceWorker.register("./sw.js", {
          scope: "./",
        });
        if (registration.installing) {
          console.info("Service worker installing");
        } else if (registration.waiting) {
          console.info("Service worker installed");
        } else if (registration.active) {
          console.info("Service worker active");
        }
      } catch (error) {
        console.error(`Registration failed with ${error}`);
      }
    }
  }

  async connectedCallback() {
    this.debug = this.getAttribute("debug") != null;

    this.registerServiceWorker();

    this.scanResults = this.querySelector("dialog");
    this.container = this.querySelector("div");

    this.cv = await loadOpenCV();
    this.hashDB = await this.initDB();

    this.createVideo();
    if (this.debug) this.createPreviewCanvas();
  }

  disconnectedCallback() {
    if (!this.video) return;
    this.scanResults.removeEventListener("close", this.startVideoHandler);
    this.startVideoButton.removeEventListener("click", this.startVideoHandler);
    this.stopVideoButton.removeEventListener("click", this.stopVideoHandler);
  }
}

customElements.define("card-scanner", CardScanner);
