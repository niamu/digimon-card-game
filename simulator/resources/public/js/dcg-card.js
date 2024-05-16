function debounce_leading(func, timeout = 300){
  let timer;
  return (...args) => {
    if (!timer) {
      func.apply(this, args);
    }
    clearTimeout(timer);
    timer = setTimeout(() => {
      timer = undefined;
    }, timeout);
  };
}

function debounce(func, timeout = 300){
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => { func.apply(this, args); }, timeout);
  };
}

class DCGCard extends HTMLElement {
  constructor() {
    super();
  }

  mouseMove = (e) => {
    this.setAttribute("interacting", "");
    if (e.type === "touchmove") {
      e.preventDefault();
      const touches = e.changedTouches;
      e.offsetX = Math.min(Math.max(touches[0].clientX - this.left, 0), this.width);
      e.offsetY = Math.min(Math.max(touches[0].clientY - this.top, 0), this.height);
    }

    const percent = {
      x: ((((e.offsetX - this.center.x) / this.center.x) / 3.5) * -1),
      y: (((e.offsetY - this.center.y) / this.center.y) / 2),
    };
    this.style.setProperty("--card-rotate-percent-x", percent.x.toFixed(3));
    this.style.setProperty("--card-rotate-percent-y", percent.y.toFixed(3));
    this.style.setProperty("--pointer-x", Math.round((e.offsetX / this.width) * 100) + "%");
    this.style.setProperty("--pointer-y", Math.round((e.offsetY / this.height) * 100) + "%");
    let deltaX = Math.abs(e.offsetX - this.center.x) / this.center.x;
    let deltaY = Math.abs(e.offsetY - this.center.y) / this.center.y;
    let delta = Math.sqrt((deltaX * deltaX) + (deltaY * deltaY)) * 100;
    delta = Math.min(Math.max(delta, 50), 100);
    this.style.setProperty("--glare-opacity", Math.round(delta));
  };

  mouseOut = () => {
    this.removeAttribute("interacting");
    this.removeAttribute("style");
  };

  calculateDimensions = () => {
    if (!this.picture) return;
    const style = this.picture.getBoundingClientRect();
    this.left = style.left;
    this.top = style.top;
    this.width = style.width;
    this.height = style.width / (this.image.width / this.image.height);
    this.center = {
      x: this.width / 2,
      y: this.height / 2,
    };
  }

  connectedCallback() {
    this.picture = this.querySelector("picture");
    if (this.getAttribute("showcase") != null && this.picture != null) {
      this.image = this.querySelector("picture img");
      this.debouncedInteract = debounce_leading(this.mouseMove, 10);
      this.debouncedResize = debounce(this.calculateDimensions, 100);
      this.calculateDimensions();

      if ("ontouchstart" in window) {
        this.addEventListener("touchstart", this.calculateDimensions);
        this.addEventListener("touchmove", this.debouncedInteract);
        this.addEventListener("touchend", this.mouseOut);
      } else {
        this.addEventListener("mouseenter", this.calculateDimensions);
        this.addEventListener("mousemove", this.debouncedInteract);
        this.addEventListener("mouseout", this.mouseOut);
      }

      window.addEventListener("resize", this.debouncedResize);
    }
  }

  disconnectedCallback() {
    this.removeEventListener("touchmove", this.debouncedInteract);
    this.removeEventListener("mousemove", this.debouncedInteract);
    this.removeEventListener("touchend", this.mouseOut);
    this.removeEventListener("mouseout", this.mouseOut);
    window.removeEventListener("resize", this.debouncedResize);
  }
}

customElements.define("dcg-card", DCGCard);
