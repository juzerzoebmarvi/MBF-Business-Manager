#!/bin/bash
cd "$(dirname "$0")" || exit 1
PORT=3000

for P in 3000 3001 3005 3010 8090; do
  if ! lsof -i :$P >/dev/null 2>&1; then
    PORT=$P
    break
  fi
done

URL="http://localhost:$PORT/"
echo "================================================================"
echo "  Business Manager - Desktop & Laptop Web App"
echo "  Starting local server at $URL"
echo "================================================================"

(
  sleep 1
  if command -v open >/dev/null 2>&1; then
    open "$URL"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$URL"
  fi
) &

if command -v python3 >/dev/null 2>&1; then
  exec python3 -m http.server "$PORT" --bind 127.0.0.1
elif command -v node >/dev/null 2>&1; then
  exec node -e "const http=require('http'),fs=require('fs'),path=require('path');const m={'.html':'text/html; charset=utf-8','.js':'application/javascript','.json':'application/json','.png':'image/png','.ico':'image/x-icon'};http.createServer((q,s)=>{let u=q.url.split('?')[0];if(u==='/health'){s.end('OK');return}let f=path.join(__dirname,u==='/'?'index.html':u.replace(/^\/+/,''));if(!fs.existsSync(f)||fs.statSync(f).isDirectory())f=path.join(__dirname,'index.html');s.writeHead(200,{'Content-Type':m[path.extname(f)]||'application/octet-stream'});fs.createReadStream(f).pipe(s)}).listen($PORT,'127.0.0.1',()=>console.log('Running on $URL'));"
else
  echo "Python 3 or Node.js is required to start the local web server."
  read -r -p "Press Enter to exit..."
fi
