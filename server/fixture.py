#!/usr/bin/env python3
"""Loopback delivery design fixture. Controls and observations are outside the shop UI."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from pathlib import Path
import argparse,json,re,threading,time
DEFAULT=dict(cityDelayMs=300,northDelayMs=300,relatedDelayMs=2500,bodyDelayMs=0,estimateHttp=200,relatedHttp=200,estimateMode='available',validForMillis=30000,schema=1,feeMinorOverride=None)
class State:
    def __init__(self,path):self.lock=threading.RLock();self.control=DEFAULT.copy();self.events=[];self.path=path
    def event(self,**row):
        with self.lock:
            row={'sequence':len(self.events)+1,**row};self.events.append(row)
            if self.path:
                with self.path.open('a') as f:f.write(json.dumps(row,separators=(',',':'))+'\n')
    def view(self):
        with self.lock:return dict(control=self.control.copy(),events=self.events.copy())
class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def send(self,status,payload,body_delay=0,on_headers=None):
        raw=json.dumps(payload,separators=(',',':')).encode() if not isinstance(payload,bytes) else payload
        self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(raw)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.flush()
        if on_headers:on_headers()
        if body_delay:time.sleep(body_delay/1000)
        self.wfile.write(raw);return len(raw)
    def do_GET(self):
        self.connection.settimeout(8);s=self.server.state;parts=urlsplit(self.path)
        if parts.path=='/admin/events' and not parts.query:return self.send(200,s.view())
        routes={'/v1/delivery-estimate':'estimate','/v1/related':'related'}
        if parts.path not in routes:return self.send(404,{'error':'not_found'})
        route=routes[parts.path];q=parse_qs(parts.query,keep_blank_values=True)
        keys={'product','zone','quantity'} if route=='estimate' else {'product'}
        if set(q)!=keys or any(len(v)!=1 for v in q.values()) or q['product']!=['camera']:return self.send(400,{'error':'invalid_request'})
        if route=='estimate' and (q['zone'][0] not in ['city','north'] or q['quantity'][0] not in ['1','2','3']):return self.send(400,{'error':'invalid_request'})
        attempt=self.headers.get('X-Journey-ID','')
        if not re.fullmatch('[a-f0-9]{32}',attempt):return self.send(400,{'error':'invalid_attempt'})
        control=s.view()['control'];begin=time.monotonic();s.event(route=route,attempt=attempt,kind='received')
        delay=control['relatedDelayMs'] if route=='related' else control[q['zone'][0]+'DelayMs']
        time.sleep(delay/1000)
        status=control['relatedHttp' if route=='related' else 'estimateHttp']
        if status!=200:payload={'error':'temporarily_unavailable'}
        elif route=='related':payload={'schema':control['schema'],'productIds':['bag','headphones']}
        else:
            zone=q['zone'][0];mode=control['estimateMode']
            payload=dict(schema=control['schema'],productId='camera',zone=('north' if zone=='city' else 'city') if mode=='mismatch' else zone,quantity=int(q['quantity'][0]),status='unserviceable' if mode=='unserviceable' else 'future_state' if mode=='unknown' else 'available')
            fee=control['feeMinorOverride']
            if fee is None:fee=0 if zone=='city' else 9900
            if mode!='unserviceable':payload.update(arrivalDays=2 if zone=='city' else 5,feeMinor=fee,validForMillis=control['validForMillis'],revision=7)
            payload['futureOptionalField']='ignored by schema-1 client'
            if mode=='oversized':payload['padding']='x'*17000
            if mode=='malformed':payload=b'{"schema":'
        try:
            size=self.send(status,payload,control['bodyDelayMs'],lambda:s.event(route=route,attempt=attempt,kind='headers',status=status,elapsedMillis=int((time.monotonic()-begin)*1000)));s.event(route=route,attempt=attempt,kind='written',status=status,bodyBytes=size,elapsedMillis=int((time.monotonic()-begin)*1000))
        except (BrokenPipeError,ConnectionResetError,TimeoutError):
            s.event(route=route,attempt=attempt,kind='disconnected',status=status,elapsedMillis=int((time.monotonic()-begin)*1000))
    def do_POST(self):
        self.connection.settimeout(3);s=self.server.state
        try:
            sizes=self.headers.get_all('Content-Length',[])
            if self.headers.get('Transfer-Encoding') is not None or len(sizes)!=1 or not re.fullmatch('[0-9]{1,4}',sizes[0]):raise ValueError()
            size=int(sizes[0])
            if not 0<size<=4096:raise ValueError()
            raw=self.rfile.read(size)
            if len(raw)!=size:raise ValueError()
            body=json.loads(raw.decode('utf-8'))
            if not isinstance(body,dict):raise ValueError()
        except (ValueError,UnicodeDecodeError,TimeoutError):return self.send(400,{'error':'invalid_body'})
        if self.path=='/admin/reset':
            if body:return self.send(400,{'error':'unexpected_fields'})
            with s.lock:s.control=DEFAULT.copy();s.events=[]
            return self.send(200,{'ok':True})
        if self.path!='/admin/config':return self.send(404,{'error':'not_found'})
        if not set(body)<=set(DEFAULT):return self.send(400,{'error':'unexpected_fields'})
        for k,v in body.items():
            if k=='estimateMode':valid=type(v) is str and v in ['available','unserviceable','unknown','mismatch','oversized','malformed']
            elif k.endswith('DelayMs'):valid=type(v) is int and 0<=v<=6000
            elif k.endswith('Http'):valid=type(v) is int and v in [200,503]
            elif k=='validForMillis':valid=type(v) is int and 1<=v<=60000
            elif k=='feeMinorOverride':valid=v is None or (type(v) is int and 0<=v<=1_000_000)
            else:valid=type(v) is int and v in [1,2]
            if not valid:return self.send(400,{'error':'invalid_control'})
        with s.lock:s.control.update(body)
        return self.send(200,s.view()['control'])
def main():
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8877);p.add_argument('--log',type=Path);a=p.parse_args()
    if a.log:a.log.parent.mkdir(parents=True,exist_ok=True)
    server=ThreadingHTTPServer(('127.0.0.1',a.port),Handler);server.state=State(a.log)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()
if __name__=='__main__':main()
