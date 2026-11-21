// تشغيل:  npm i ws   ثم   node server.js   ثم افتح العنوان اللي بيطبعه
// السيرفر بيقدّم asylum_game.html بنفسه، فحط الملفين في نفس الفولدر.
const http=require('http'),fs=require('fs'),path=require('path'),{WebSocketServer}=require('ws');
const PORT=process.env.PORT||8080,FILE=[path.join(__dirname,'asylum_game.html'),path.join(__dirname,'..','app','src','main','assets','asylum_game.html')].find(f=>fs.existsSync(f))||path.join(__dirname,'asylum_game.html');
const srv=http.createServer((q,r)=>fs.readFile(FILE,(e,d)=>{if(e){r.writeHead(404);return r.end('asylum_game.html not found')}r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(d)}));
const wss=new WebSocketServer({server:srv}),rooms=new Map();let uid=1;
const send=(w,o)=>w.readyState===1&&w.send(JSON.stringify(o));
const cast=(rm,o,ex)=>{for(const w of rm.p.keys())if(w!==ex)send(w,o)};
wss.on('connection',ws=>{
 ws.id=uid++;ws.room=null;
 const join=(rm,nick)=>{ws.room=rm;ws.nick=String(nick||'لاعب').slice(0,12);rm.p.set(ws,1);send(ws,{t:'joined',id:rm.id,seed:rm.seed,host:rm.host===ws,you:ws.id})};
 const leave=()=>{const rm=ws.room;if(!rm)return;rm.p.delete(ws);ws.room=null;cast(rm,{t:'bye',id:ws.id});
  if(!rm.p.size)rooms.delete(rm.id);else if(rm.host===ws){rm.host=rm.p.keys().next().value;cast(rm,{t:'host',id:rm.host.id})}};
 ws.on('message',raw=>{let m;try{m=JSON.parse(raw)}catch(e){return}
  if(ws.room&&(m.t==='create'||m.t==='join'))return send(ws,{t:'err',m:'غادر السيرفر الحالي الأول'});
  if(m.t==='list')send(ws,{t:'list',rooms:[...rooms.values()].map(r=>({id:r.id,name:r.name,n:r.p.size,max:r.max,lock:!!r.pw,host:r.host.nick,names:[...r.p.keys()].map(w=>w.nick)}))});
  else if(m.t==='create'){const id=String(uid++),rm={id,name:String(m.name||'سيرفر').slice(0,24),max:Math.min(8,Math.max(2,(m.max|0)||4)),pw:String(m.pw||'').slice(0,32),seed:Math.random()*4e9>>>0,host:ws,p:new Map()};rooms.set(id,rm);join(rm,m.nick)}
  else if(m.t==='join'){const rm=rooms.get(String(m.id));if(!rm)return send(ws,{t:'err',m:'السيرفر ده اتقفل'});if(rm.p.size>=rm.max)return send(ws,{t:'err',m:'السيرفر ممتلئ'});if(rm.pw&&rm.pw!==m.pw)return send(ws,{t:'err',m:'كلمة السر غلط'});join(rm,m.nick)}
  else if(m.t==='leave')leave();
  else if(ws.room&&(m.t==='s'||(m.t==='m'&&ws.room.host===ws))){m.id=ws.id;m.n=ws.nick;cast(ws.room,m,ws)}});
 ws.on('close',leave)});
srv.listen(PORT,()=>console.log('افتح: http://localhost:'+PORT));
