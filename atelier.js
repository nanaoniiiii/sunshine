import {icon} from './icons.js';

document.querySelectorAll('[data-icon]').forEach(el=>el.innerHTML=icon(el.dataset.icon));
function decorateActions(){document.querySelectorAll('a.button.primary').forEach(el=>{
  if(el.querySelector('span')) return;
  if(el.textContent.endsWith('↗')){
    el.textContent=el.textContent.replace(/\s*↗$/,'');
    const arrow=document.createElement('span');
    arrow.innerHTML=icon('arrow-up-right');
    el.append(arrow);
  }
});}
decorateActions();
document.addEventListener('DOMContentLoaded',decorateActions,{once:true});
const story=document.querySelector('.signal-story');
const stories={
  video:['画面抵达，熟悉的一切都在。','Sunshine 捕获 Android 屏幕，经 AMediaCodec 硬件编码，让 Moonlight 在另一端显示同一画面。','捕获 → 编码 → 显示'],
  audio:['声音，也跟着画面一起走。','AudioPlaybackCapture 捕获允许内录的媒体音频，再经 Opus 编码传向 Moonlight。实际捕获能力受来源应用与 Android 策略限制。','媒体内录 → Opus → 播放'],
  input:['轻轻一点，操作回到你的手机。','Moonlight 的鼠标、键盘与触摸输入返回主机。SunshineMouse 处理画面比例和坐标，Shizuku 为输入注入提供权限代理。','鼠标 / 触控 → 映射 → 主机']
};
const tabs=[...document.querySelectorAll('[data-story-mode]')];
function setStory(mode,focus=false){
  story.dataset.signalMode=mode;
  tabs.forEach(tab=>{
    const active=tab.dataset.storyMode===mode;
    tab.setAttribute('aria-selected',String(active));
    tab.tabIndex=active?0:-1;
    if(active&&focus)tab.focus();
  });
  document.querySelector('#story-title').textContent=stories[mode][0];
  document.querySelector('#story-copy').textContent=stories[mode][1];
  document.querySelector('#signal-label').textContent=stories[mode][2];
  document.querySelector('#story-panel').setAttribute('aria-labelledby','tab-'+mode);
  story.classList.remove('story-change');
  requestAnimationFrame(()=>story.classList.add('story-change'));
}
tabs.forEach((tab,index)=>{
  tab.tabIndex=index===0?0:-1;
  tab.addEventListener('click',()=>setStory(tab.dataset.storyMode));
  tab.addEventListener('keydown',event=>{
    let next;
    if(event.key==='ArrowRight')next=(index+1)%tabs.length;
    if(event.key==='ArrowLeft')next=(index+tabs.length-1)%tabs.length;
    if(event.key==='Home')next=0;
    if(event.key==='End')next=tabs.length-1;
    if(next===undefined)return;
    event.preventDefault();
    setStory(tabs[next].dataset.storyMode,true);
  });
});
new IntersectionObserver(entries=>{
  for(const entry of entries)entry.target.classList.toggle('motion-visible',entry.isIntersecting);
},{threshold:.1}).observe(story);
document.addEventListener('visibilitychange',()=>document.documentElement.classList.toggle('motion-paused',document.hidden));

// Pointer depth and spring return are coordinated by motion.js.
