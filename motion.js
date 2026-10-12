// Motion patterns informed by feitangyuan/motion-web; adapted for Sunshine.
// One event-driven spring loop; native scroll timelines own scroll-linked art.
const reducedMotion=matchMedia('(prefers-reduced-motion:reduce)');
const springs=[];
let frame=0,last=0;
function schedule(){if(!frame&&!document.hidden&&!reducedMotion.matches){last=performance.now();frame=requestAnimationFrame(tick);}}
function tick(now){
  frame=0;
  const dt=Math.min((now-last)/1000,1/30);last=now;
  let moving=false;
  for(const s of springs){
    s.vx+=(350*(s.tx-s.x)-28*s.vx)*dt;
    s.vy+=(350*(s.ty-s.y)-28*s.vy)*dt;
    s.x+=s.vx*dt;s.y+=s.vy*dt;
    const unsettled=Math.abs(s.tx-s.x)+Math.abs(s.ty-s.y)+Math.abs(s.vx)+Math.abs(s.vy)>.015;
    if(!unsettled){s.x=s.tx;s.y=s.ty;s.vx=s.vy=0;}
    s.paint(s.x,s.y,unsettled);moving||=unsettled;
  }
  if(moving&&!document.hidden&&!reducedMotion.matches)frame=requestAnimationFrame(tick);
}
function spring(paint){const s={x:0,y:0,vx:0,vy:0,tx:0,ty:0,paint};springs.push(s);return s;}
function resetSprings(){cancelAnimationFrame(frame);frame=0;for(const s of springs){s.x=s.y=s.tx=s.ty=s.vx=s.vy=0;s.paint(0,0,false);}}

function magneticActions(){
  if(!matchMedia('(hover:hover) and (pointer:fine)').matches)return;
  document.querySelectorAll('.home-page a.button.primary').forEach(el=>{
    const s=spring((x,y,active)=>{el.style.translate=`${x.toFixed(2)}px ${y.toFixed(2)}px`;el.style.willChange=active?'translate':'auto';});
    el.addEventListener('pointermove',e=>{
      if(reducedMotion.matches)return;
      const r=el.getBoundingClientRect();
      s.tx=Math.max(-7,Math.min(7,(e.clientX-(r.left-s.x+r.width/2))*.12));
      s.ty=Math.max(-5,Math.min(5,(e.clientY-(r.top-s.y+r.height/2))*.18));schedule();
    });
    el.addEventListener('pointerleave',()=>{s.tx=s.ty=0;schedule();});
    el.addEventListener('blur',()=>{s.tx=s.ty=0;schedule();});
  });
  document.querySelectorAll('.hover-depth').forEach(el=>{
    const s=spring((x,y,active)=>{el.style.setProperty('--tilt-x',`${x.toFixed(3)}deg`);el.style.setProperty('--tilt-y',`${y.toFixed(3)}deg`);el.style.willChange=active?'transform':'auto';});
    el.addEventListener('pointermove',e=>{if(reducedMotion.matches)return;const r=el.getBoundingClientRect();s.tx=(e.clientY-r.top-r.height/2)/r.height*-3;s.ty=(e.clientX-r.left-r.width/2)/r.width*3;schedule();});
    el.addEventListener('pointerleave',()=>{s.tx=s.ty=0;schedule();});
  });
}

function lineReveal(){
  const heading=document.querySelector('.hero-heading h1');
  heading.innerHTML='<span class="motion-line"><span>更大的画面。</span></span><span class="motion-line accent-line"><span>同一个 Android。</span></span>';
  if(reducedMotion.matches)return;
  heading.querySelectorAll('.motion-line>span').forEach((line,i)=>line.animate([{transform:'translateY(105%)'},{transform:'translateY(0)'}],{duration:760,delay:100+i*110,easing:'cubic-bezier(.16,1,.3,1)'}));
}

function tabHandoff(){
  const track=document.querySelector('.story-tabs');
  const pill=document.createElement('span');pill.className='story-selection';pill.setAttribute('aria-hidden','true');track.prepend(pill);
  let previous=null,animation;
  function place(){
    const selected=track.querySelector('[aria-selected="true"]');if(!selected||!track.offsetWidth)return;
    const next={x:selected.offsetLeft,y:selected.offsetTop,w:selected.offsetWidth,h:selected.offsetHeight};
    pill.style.width=next.w+'px';pill.style.height=next.h+'px';pill.style.left=next.x+'px';pill.style.top=next.y+'px';
    animation?.cancel();
    if(previous&&!reducedMotion.matches)animation=pill.animate([{transform:`translateX(${previous.x-next.x}px) scaleX(${previous.w/next.w})`},{transform:'translateX(0) scaleX(1)'}],{duration:440,easing:'cubic-bezier(.16,1,.3,1)'});
    previous=next;
  }
  new MutationObserver(place).observe(track,{attributes:true,subtree:true,attributeFilter:['aria-selected']});
  new ResizeObserver(place).observe(track);place();
  document.fonts.ready.then(place);
}

function selectiveReveal(){
  const visual=document.querySelector('.signal-visual');
  const observer=new IntersectionObserver(entries=>{if(!entries.some(e=>e.isIntersecting))return;observer.disconnect();if(!reducedMotion.matches)visual.animate([{clipPath:'inset(0 48% 0 48% round 18px)'},{clipPath:'inset(0 0 0 0 round 0px)'}],{duration:800,easing:'cubic-bezier(.76,0,.24,1)'});},{threshold:.18});
  observer.observe(visual);
}

function guideCards(){
  const list=document.querySelector('.guide-list');
  const statuses=['授予权限','监听主机','发现设备','确认 PIN','显示桌面','返回输入'];
  [...list.children].forEach((card,i)=>{
    card.style.setProperty('--card-index',i);
    const stage=document.createElement('div');stage.className='guide-signal';stage.setAttribute('aria-hidden','true');
    stage.innerHTML=`<span>SUNSHINE</span><i></i><b>${statuses[i]}</b><i></i><span>MOONLIGHT</span>`;card.append(stage);
  });
  const observer=new IntersectionObserver(entries=>{
    for(const entry of entries)if(entry.isIntersecting){entry.target.classList.add('guide-active');observer.unobserve(entry.target);}
  },{threshold:.6});
  [...list.children].forEach(el=>observer.observe(el));
}

function demoMotion(){
  let lastStep=0,lastStream=false;
  document.addEventListener('sunshine:demo-render',event=>{
    const {step,stream}=event.detail;
    const root=document.querySelector('#demo-root');
    root.style.setProperty('--session-progress',step/5);
    root.style.setProperty('--previous-progress',lastStep/5);
    if(!reducedMotion.matches){
      root.querySelectorAll('.feedback>span').forEach((dot,i)=>dot.animate([{transform:'scale(1)',opacity:1},{transform:'scale(2.2)',opacity:.35},{transform:'scale(1)',opacity:1}],{duration:650,delay:i*110,easing:'cubic-bezier(.16,1,.3,1)'}));
      if(stream&&!lastStream)root.querySelectorAll('.screen-surface').forEach((surface,i)=>surface.animate([{opacity:.25},{opacity:1}],{duration:560,delay:i*80}));
    }
    lastStep=step;lastStream=stream;
  });
}

function init(){lineReveal();magneticActions();tabHandoff();selectiveReveal();guideCards();demoMotion();}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init,{once:true});else init();
document.addEventListener('visibilitychange',()=>{if(document.hidden)resetSprings();});
reducedMotion.addEventListener('change',()=>{if(reducedMotion.matches){resetSprings();document.getAnimations().forEach(a=>a.cancel());}});
