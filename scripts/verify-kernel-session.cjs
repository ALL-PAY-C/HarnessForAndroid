const fs = require('fs'), path = require('path'), http = require('http');
const { spawn } = require('child_process');
const stage = path.resolve(process.argv[2]);
const server = http.createServer((req, res) => {
  let body = ''; req.on('data', c => body += c); req.on('end', () => {
    if (!req.url.endsWith('/messages') || !body.includes('T11_KERNEL_SESSION_42')) { res.writeHead(400); res.end(); return; }
    res.writeHead(200, {'Content-Type':'text/event-stream'});
    const event = (type, data) => res.write('event: '+type+'\ndata: '+JSON.stringify({type,...data})+'\n\n');
    event('message_start',{message:{id:'kernel-gate',type:'message',role:'assistant',model:'deepseek-flash',content:[],stop_reason:null,usage:{input_tokens:10,output_tokens:0}}});
    event('content_block_start',{index:0,content_block:{type:'text',text:''}});
    event('content_block_delta',{index:0,delta:{type:'text_delta',text:'T11_KERNEL_SESSION_42'}});
    event('content_block_stop',{index:0});
    event('message_delta',{delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:8}});
    event('message_stop',{}); res.end();
  });
});
server.listen(0,'127.0.0.1',()=>{
  const child = spawn(process.execPath,[path.join(stage,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'headless','Print T11_KERNEL_SESSION_42'],{cwd:stage,env:{...process.env,DSH_HOME:stage,DSH_TELEMETRY_DISABLED:'1',DEEPSEEK_API_KEY:'kernel-gate',DEEPSEEK_BASE_URL:'http://127.0.0.1:'+server.address().port},stdio:['ignore','pipe','pipe']});
  let out='',err=''; child.stdout.on('data',c=>out+=c); child.stderr.on('data',c=>err+=c);
  const timer=setTimeout(()=>child.kill(),60000);
  child.on('close',code=>{clearTimeout(timer);server.close();process.stdout.write(out);process.stderr.write(err);if(code!==0||out.trim()!=='T11_KERNEL_SESSION_42') process.exitCode=1;else console.log('KERNEL_SESSION_GATE=PASS RC=0');});
});
