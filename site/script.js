const root = document.documentElement;
const toggle = document.querySelector(".language-toggle");
const currentLanguage = localStorage.getItem("deskcontrol-language")
    || (navigator.language.toLowerCase().startsWith("zh") ? "zh" : "en");

function setLanguage(language) {
    const isChinese = language === "zh";
    root.lang = isChinese ? "zh-CN" : "en";
    document.querySelectorAll("[data-zh][data-en]").forEach((element) => {
        element.innerHTML = element.dataset[language];
    });
    document.querySelector(".lang-current").textContent = isChinese ? "中" : "EN";
    document.querySelector(".lang-other").textContent = isChinese ? "EN" : "中";
    toggle.setAttribute("aria-label", isChinese ? "Switch to English" : "切换到中文");
    document.title = isChinese
        ? "DeskControl — 让外接屏真正可用"
        : "DeskControl — Real control for your second screen";
    localStorage.setItem("deskcontrol-language", language);
}

setLanguage(currentLanguage);

toggle.addEventListener("click", () => {
    setLanguage(root.lang === "zh-CN" ? "en" : "zh");
});

document.querySelector("#year").textContent = new Date().getFullYear();

const touchSurface = document.querySelector(".touch-surface");
const trace = document.querySelector(".finger-trace");
const cursor = document.querySelector(".demo-cursor");

function moveDemoCursor(event) {
    const bounds = touchSurface.getBoundingClientRect();
    const x = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width));
    const y = Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height));
    trace.style.left = `${x * 100}%`;
    trace.style.top = `${y * 100}%`;
    cursor.style.left = `${18 + x * 65}%`;
    cursor.style.top = `${18 + y * 64}%`;
}

touchSurface.addEventListener("pointermove", moveDemoCursor);
