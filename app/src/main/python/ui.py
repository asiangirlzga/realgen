INDEX_HTML = r"""<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RealGen</title>
<style>
:root{color-scheme:dark}
body{margin:0;font:15px system-ui,sans-serif;background:#0f1115;color:#e8e8ea;padding:14px}
h1{font-size:18px;margin:0 0 6px}
label{display:block;font-size:12px;color:#9aa4b2;margin:10px 0 4px}
textarea,select,input[type=text],input[type=password]{width:100%;box-sizing:border-box;background:#1a1d24;color:#fff;border:1px solid #2c313c;border-radius:10px;padding:10px;font:inherit}
textarea{min-height:84px}
.row{display:flex;gap:8px}.row>div{flex:1}
button{width:100%;margin-top:14px;padding:13px;border:0;border-radius:12px;background:#4f7cff;color:#fff;font-weight:600;font-size:16px}
button.sec{background:#2c313c;margin-top:8px;display:none}
button:disabled{opacity:.5}
#status{margin:12px 0;color:#9ab;font-size:13px;min-height:18px}
#grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.card{position:relative}
.card img{width:100%;border-radius:10px;display:block;background:#1a1d24}
.card button{position:absolute;right:6px;bottom:6px;width:auto;margin:0;padding:6px 12px;font-size:12px;background:#000b}
#view{position:fixed;inset:0;background:#000;display:none;align-items:center;justify-content:center}
#view img{max-width:100%;max-height:100%}
.chk{display:flex;align-items:center;gap:8px;margin-top:10px;font-size:13px;color:#9aa4b2}
small{color:#6c7686}
</style></head><body>
<h1>RealGen</h1>
<small>Realistic AI images, 10 per batch</small>

<label>Prompt</label>
<textarea id="prompt" placeholder="e.g. 28 year old woman, brown eyes, freckles, wearing a denim jacket, city street at golden hour"></textarea>

<label>Negative prompt (optional)</label>
<textarea id="neg" style="min-height:50px" placeholder="leave empty for a good default"></textarea>

<div class="row">
  <div><label>Images</label><select id="count">
    <option>10</option><option>8</option><option>6</option><option>4</option><option>2</option><option>1</option></select></div>
  <div><label>Size</label><select id="size">
    <option value="512x768">512x768 portrait</option>
    <option value="512x512">512x512 square</option>
    <option value="768x512">768x512 landscape</option></select></div>
</div>

<label>Model</label>
<select id="model"><option>(auto)</option></select>

<label>Stable Horde API key (optional, faster queue)</label>
<input type="password" id="key" placeholder="leave empty for anonymous">

<div class="chk"><input type="checkbox" id="face"><span>Face fix (slower, better faces)</span></div>

<button id="go">Generate</button>
<button id="stop" class="sec">Cancel</button>
<div id="status"></div>
<div id="grid"></div>
<div id="view"><img id="vimg"></div>

<script>
const $=id=>document.getElementById(id);
let jid=null,shown=0;
try{$('key').value=localStorage.getItem('hkey')||''}catch(e){}

async function api(p,o){const r=await fetch(p,o);return r.json()}
async function loadModels(){
  try{const m=await api('/api/models');$('model').innerHTML=m.map(x=>'<option>'+x+'</option>').join('')}catch(e){}
}
loadModels();

function addImg(i){
  const url='/api/img/'+jid+'/'+i;
  const d=document.createElement('div');d.className='card';
  const im=document.createElement('img');im.src=url;im.onclick=()=>{$('vimg').src=url;$('view').style.display='flex'};
  const b=document.createElement('button');b.textContent='Save';
  b.onclick=()=>{if(window.Android){Android.save(url)}else{window.open(url)}};
  d.appendChild(im);d.appendChild(b);$('grid').appendChild(d);
}
$('view').onclick=()=>{$('view').style.display='none'};

function finish(){$('go').disabled=false;$('stop').style.display='none'}

async function poll(){
  try{
    const j=await api('/api/job/'+jid);
    $('status').textContent=j.message;
    for(;shown<j.images;shown++)addImg(shown);
    if(j.state!=='running'){finish();return}
  }catch(e){$('status').textContent='Connection issue, retrying...'}
  setTimeout(poll,2500);
}

$('go').onclick=async()=>{
  try{localStorage.setItem('hkey',$('key').value)}catch(e){}
  $('grid').innerHTML='';shown=0;$('go').disabled=true;$('stop').style.display='block';
  $('status').textContent='Starting...';
  const body={prompt:$('prompt').value,negative:$('neg').value,count:+$('count').value,
    size:$('size').value,model:$('model').value,key:$('key').value,face:$('face').checked};
  try{
    const r=await api('/api/start',{method:'POST',body:JSON.stringify(body)});
    jid=r.id;poll();
  }catch(e){$('status').textContent='Failed to start: '+e;finish()}
};
$('stop').onclick=()=>{if(jid)api('/api/cancel/'+jid,{method:'POST'})};
</script>
</body></html>
"""
