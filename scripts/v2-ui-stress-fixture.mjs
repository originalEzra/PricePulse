import http from 'node:http'
import net from 'node:net'
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync, createWriteStream, existsSync } from 'node:fs'
import { resolve } from 'node:path'

// Opt-in, localhost-only UI fixture. It never connects to a business database, provider, or broker.
const reportPath=process.argv.find(arg=>arg.startsWith('--report-dir='))?.slice('--report-dir='.length)
const directory=resolve(reportPath??'target/v2-p5/ui')
if(existsSync(directory+'/stop.signal'))throw new Error('Choose a fresh report directory; the previous stop signal is preserved.')
mkdirSync(directory,{recursive:true})
const prefix=[]
const session=day=>{const date=new Date(day+'T00:00:00Z'),hour=day<'2026-11-01'?13:14;return {date:day,open:new Date(Date.UTC(2026,date.getUTCMonth(),date.getUTCDate(),hour,30)).toISOString(),close:new Date(Date.UTC(2026,date.getUTCMonth(),date.getUTCDate(),hour+7,0)).toISOString()}}
const sessions=['2026-10-27','2026-10-28'].map(session)
for(const s of sessions)for(let i=1;i<=390;i++){
  const eventTime=new Date(Date.parse(s.open)+i*60000).toISOString(),price=(100+Math.sin(i/30)*.45).toFixed(4)
  prefix.push({pointRef:'bar:'+prefix.length,eventTime,epochNanos:String(BigInt(Date.parse(eventTime))*1000000n),price,kind:'BAR_CLOSE',intervalStart:new Date(Date.parse(eventTime)-60000).toISOString(),intervalEnd:eventTime,availableAt:eventTime,revision:'1',session:s.date})
}
const points=[]
for(let day=new Date('2026-10-29T00:00:00Z');points.length<6000;day=new Date(day.getTime()+86400000)){
  if([0,6].includes(day.getUTCDay()))continue
  const s=session(day.toISOString().slice(0,10));sessions.push(s)
  for(let i=1;i<=390&&points.length<6000;i++)points.push({pointSeq:points.length+1,eventTime:new Date(Date.parse(s.open)+i*60000).toISOString(),price:(100+Math.sin(points.length/24)*4+points.length*.0001).toFixed(4)})
}
const snapshots=Array.from({length:1000},(_,i)=>({snapshotRuleId:i+1,sourceRuleId:i+1,sourceVersion:0,sourceStatus:'ACTIVE',initialState:'ACTIVE',currentState:'COMPLETED',stateVersion:1,
  config:{id:i+1,stockId:1,stockSymbol:'AAPL',stockName:'合成压力验收 Apple',name:`合成重叠规则 ${i+1}`,direction:'ABOVE',thresholdPrice:'100.2000',triggerMode:'ONCE',rearmPrice:null,status:'ACTIVE',version:0,note:'Synthetic UI stress fixture',createdAt:'2026-10-28T13:30:00Z',updatedAt:'2026-10-28T13:30:00Z',lastTriggeredAt:null}}))
const densePoints=points.slice(0,60).map((p,i)=>({...p,price:(100.4+i*.002).toFixed(4)}))
const denseEvents=snapshots.map((s,i)=>({id:i+1,snapshotRuleId:s.snapshotRuleId,pointSeq:1,eventType:'TRIGGERED',eventTime:densePoints[0].eventTime,epochNanos:String(BigInt(Date.parse(densePoints[0].eventTime))*1000000n),price:densePoints[0].price,fromState:'ACTIVE',toState:'COMPLETED',ruleSnapshot:s.config}))
const origin={kind:'HISTORICAL_POINT',stockSymbol:'AAPL',provider:'ALPACA',feed:'iex',point:prefix.at(-1),prefixFrom:sessions[0].open,calendarVersion:'SYNTHETIC_UI_STRESS_ONLY'}
function dataset(dense){const simulation=dense?densePoints:points,runId=dense?'11111111-2222-4333-8444-555555555556':'11111111-2222-4333-8444-555555555555',version=simulation.length/60
  const run={runId,stockId:1,version,lastBatchNo:version,simClock:simulation.at(-1).eventTime,latestPrice:simulation.at(-1).price,nextPointSeq:simulation.length+1,seed:'42',generatorVersion:'SYNTHETIC_UI_STRESS_ONLY',generatorSupported:false,origin,parameters:{mode:'NORMAL',stepRatio:'0.003'},ruleSnapshots:dense?snapshots:[],stats:{triggered:dense?1000:0,rearmed:0}}
  return {run,simulation,events:dense?denseEvents:[]}}
const datasets={long:dataset(false),dense:dataset(true)},requests=[]
const stock={id:1,symbol:'AAPL',name:'合成压力验收 Apple',market:'US',currency:'USD',latestPrice:100.1754,latestQuoteAt:origin.point.eventTime}
const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://fixture'),path=url.pathname,which=req.headers.authorization?.endsWith('dense')?'dense':'long',data=datasets[which]
  requests.push({method:req.method,path});let body='';for await(const chunk of req)body+=chunk
  const send=(data,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({success:status===200,data,error:status===200?null:{code:'SYNTHETIC_NOT_FOUND',message:'Read-only synthetic fixture'}}))}
  if(path==='/api/auth/login'){const dense=JSON.parse(body).email.startsWith('dense');return send({accessToken:'synthetic-p5-'+(dense?'dense':'long'),tokenType:'Bearer',expiresAt:new Date(Date.now()+3600000).toISOString(),user:{id:dense?2:1,email:dense?'dense@test.local':'long@test.local',displayName:'合成压力验收（非实盘）'}})}
  if(path==='/api/auth/logout')return send({message:'Signed out'})
  if(path==='/api/notifications/feed')return send({totalCount:0,unreadCount:0,latest:[]})
  if(path==='/api/stocks')return send([stock])
  if(path==='/api/alert-rules')return send([])
  if(path==='/api/simulations/current')return send(data.run)
  if(path.endsWith('/timeline')&&path.startsWith('/api/simulations/')){
    const after=Number(url.searchParams.get('cursor')??0),part=data.simulation.slice(after,after+600)
    return send({scope:'SIMULATION',...data.run,range:{from:origin.prefixFrom,to:data.run.simClock,marketTimeZone:'America/New_York'},sessions,realPrefix:prefix,simulation:part,thresholds:data.run.ruleSnapshots,pointCursor:after+part.length<data.simulation.length?String(after+part.length):null,watermark:String(data.run.version)})}
  if(path.endsWith('/events')&&path.startsWith('/api/simulations/')){const after=Number(url.searchParams.get('cursor')??0),part=data.events.slice(after,after+100);return send({events:part,eventCursor:after+part.length<data.events.length?String(after+part.length):null,watermark:String(data.events.length)})}
  if(path==='/api/market/stocks/1/timeline')return send({scope:'REAL',provider:'ALPACA',feed:'iex',adjustment:'raw',resolution:'1Min',stock:{...stock,latestPrice:String(stock.latestPrice),priceKind:'HISTORICAL_BAR_CLOSE'},range:{from:sessions[1].open,to:sessions[1].close,asOf:sessions[1].close,marketTimeZone:'America/New_York'},coverage:{requestedFrom:sessions[1].open,requestedTo:sessions[1].close,syncedAt:sessions[1].close},health:{connection:'DISABLED',market:'CLOSED',freshness:'MARKET_CLOSED',businessWritesEnabled:false,lastError:null},points:prefix.slice(-390),gaps:[],sessions:[sessions[1]],thresholds:[],events:[],eventCursor:null,watermark:'0'})
  send(null,404)
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const reserve=net.createServer();await new Promise(resolve=>reserve.listen(0,'127.0.0.1',resolve));const port=reserve.address().port;await new Promise(resolve=>reserve.close(resolve))
const log=createWriteStream(resolve(directory,'vite.log')),vite=spawn(process.execPath,['node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',String(port),'--strictPort'],{cwd:resolve('frontend'),env:{...process.env,PRICEPULSE_BACKEND_URL:`http://127.0.0.1:${server.address().port}`},stdio:['ignore','pipe','pipe']})
vite.stdout.pipe(log);vite.stderr.pipe(log)
const metadata={fixture:'SYNTHETIC_UI_STRESS_NOT_LIVE',url:`http://127.0.0.1:${port}`,developmentDatabaseConnected:false,providerConnected:false,longPoints:6000,overlappingEvents:1000}
writeFileSync(resolve(directory,'fixture.json'),JSON.stringify(metadata,null,2));console.log(JSON.stringify(metadata))
await new Promise(resolve=>{const start=Date.now(),timer=setInterval(()=>{if(existsSync(directory+'/stop.signal')||Date.now()-start>1200000){clearInterval(timer);resolve()}},250)})
writeFileSync(resolve(directory,'requests.json'),JSON.stringify({fixture:metadata.fixture,requests},null,2));vite.kill('SIGTERM');server.close();log.end();console.log('UI_STRESS_CLOSED')
