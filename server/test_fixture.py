#!/usr/bin/env python3
"""Independent actual-HTTP fixture contract tests. Own process/port; no Android tooling."""
import argparse, hashlib, http.client, json, pathlib, socket, subprocess, sys, threading, time, unittest
from urllib.parse import urlencode
ROOT = pathlib.Path(__file__).resolve().parent
PORT = 8878
ATTEMPT = 'a' * 32
STAGES = []

def request(path, payload=None, headers=None, raw=None):
    connection = http.client.HTTPConnection('127.0.0.1', PORT, timeout=8)
    try:
        h = dict(headers or {})
        body = json.dumps(payload).encode() if payload is not None else raw
        connection.request('POST' if body is not None else 'GET', path, body=body, headers=h)
        response = connection.getresponse(); data = response.read()
        return response.status, dict(response.getheaders()), data
    finally:
        connection.close()

def config(**fields):
    status, _, raw = request('/admin/config', fields)
    if status != 200: raise AssertionError((status, raw))

def estimate(zone='city', quantity='1', attempt=ATTEMPT):
    return request('/v1/delivery-estimate?' + urlencode(dict(product='camera', zone=zone, quantity=quantity)), headers={'X-Journey-ID': attempt})

def snapshot(): return json.loads(request('/admin/events')[2])

class FixtureContractTest(unittest.TestCase):
    def setUp(self):
        self.assertEqual(200, request('/admin/reset', {})[0])
        config(cityDelayMs=0, northDelayMs=0, relatedDelayMs=0)
    def tearDown(self):
        STAGES.append({'case':self._testMethodName, 'snapshot':snapshot()})
    def test_01_scope_values_are_echoed_with_service_owned_terms(self):
        for zone, days, fee in [('city',2,0), ('north',5,9900)]:
            for quantity in ('1','2','3'):
                status, headers, raw = estimate(zone, quantity)
                self.assertEqual(200,status); body=json.loads(raw)
                self.assertEqual((zone,int(quantity),days,fee), (body['zone'],body['quantity'],body['arrivalDays'],body['feeMinor']))
                self.assertEqual('no-store',headers['Cache-Control'])
                self.assertEqual(len(raw),int(headers['Content-Length']))
                self.assertEqual(1,body['schema']); self.assertEqual(7,body['revision'])
        self.assertEqual(6,len([e for e in snapshot()['events'] if e['kind']=='received']))
    def test_02_invalid_or_ambiguous_request_key_does_not_start_work(self):
        queries=['product=camera&zone=city&quantity=0','product=camera&zone=city&quantity=4',
                 'product=camera&zone=city&quantity=01','product=camera&zone=City&quantity=1',
                 'product=tea&zone=city&quantity=1','product=camera&zone=city&quantity=1&quantity=2',
                 'product=camera&zone=city&quantity=1&postal=private-value','product=camera&zone=city']
        for query in queries:
            self.assertEqual(400,request('/v1/delivery-estimate?'+query,headers={'X-Journey-ID':ATTEMPT})[0])
        self.assertEqual([],snapshot()['events'])
    def test_03_correlation_grammar_and_diagnostic_allowlist(self):
        for attempt in ['', 'A'*32,'customer@example.com','a'*31,'a'*33]:
            self.assertEqual(400,estimate(attempt=attempt)[0])
        self.assertEqual(200,estimate()[0])
        for event in snapshot()['events']:
            self.assertLessEqual(set(event), {'sequence','route','attempt','kind','status','bodyBytes','elapsedMillis'})
            self.assertNotIn('zone',event); self.assertNotIn('quantity',event)
        serialized=json.dumps(snapshot()['events'])
        self.assertNotIn('customer@example.com',serialized)
    def test_04_control_validation_is_closed_and_atomic(self):
        for fields in [{'unknown':1},{'cityDelayMs':True},{'cityDelayMs':-1},{'cityDelayMs':6001},{'bodyDelayMs':-1},{'bodyDelayMs':6001},{'bodyDelayMs':True},
                       {'estimateHttp':401},{'validForMillis':0},{'validForMillis':60001},
                       {'feeMinorOverride':True},{'feeMinorOverride':-1},{'feeMinorOverride':1000001},{'schema':True},{'schema':3},{'estimateMode':'AVAILABLE'},
                       {'cityDelayMs':250,'relatedHttp':False}]:
            before=snapshot()['control']
            self.assertEqual(400,request('/admin/config',fields)[0]); self.assertEqual(before,snapshot()['control'])
    def test_05_domain_unserviceable_is_distinct_from_http_failure(self):
        config(estimateMode='unserviceable')
        status,_,raw=estimate(); body=json.loads(raw)
        self.assertEqual(200,status); self.assertEqual('unserviceable',body['status'])
        self.assertNotIn('feeMinor',body); self.assertNotIn('arrivalDays',body)
        config(estimateHttp=503)
        status,_,raw=estimate(); self.assertEqual(503,status)
        self.assertEqual({'error':'temporarily_unavailable'},json.loads(raw))
    def test_06_fault_modes_are_actual_response_differences(self):
        for mode in ['unknown','mismatch','oversized','malformed']:
            config(estimateMode=mode); status,_,raw=estimate(); self.assertEqual(200,status)
            if mode=='unknown': self.assertEqual('future_state',json.loads(raw)['status'])
            elif mode=='mismatch': self.assertEqual('north',json.loads(raw)['zone'])
            elif mode=='oversized': self.assertGreater(len(raw),16384)
            else:
                with self.assertRaises(json.JSONDecodeError): json.loads(raw)
        config(estimateMode='available',schema=2)
        self.assertEqual(2,json.loads(estimate()[2])['schema'])
    def test_07_inflight_request_uses_its_admitted_control_snapshot(self):
        config(cityDelayMs=300,estimateMode='available'); result=[]
        thread=threading.Thread(target=lambda:result.append(estimate())); thread.start()
        until=time.monotonic()+3
        while not snapshot()['events'] and time.monotonic()<until: time.sleep(.01)
        self.assertTrue(snapshot()['events']); config(estimateMode='unserviceable')
        thread.join(3); self.assertFalse(thread.is_alive())
        self.assertEqual('available',json.loads(result[0][2])['status'])
        self.assertEqual('unserviceable',json.loads(estimate()[2])['status'])
    def test_08_malformed_admin_bodies_are_rejected(self):
        for raw in [b'[]',b'null',b'{',b'\xff',b'{"cityDelayMs":null}']:
            self.assertEqual(400,request('/admin/config',raw=raw)[0])
        self.assertEqual(400,request('/admin/reset',{'extra':1})[0])
        self.assertEqual(404,request('/v2/delivery-estimate')[0])
    def test_11_fractional_fee_override_preserves_minor_units_and_can_be_reset(self):
        config(feeMinorOverride=9901)
        self.assertEqual(9901,json.loads(estimate()[2])['feeMinor'])
        config(feeMinorOverride=None)
        self.assertEqual(0,json.loads(estimate()[2])['feeMinor'])
        self.assertEqual(9900,json.loads(estimate('north')[2])['feeMinor'])
    def test_10_body_delay_has_a_visible_headers_boundary(self):
        config(bodyDelayMs=200)
        connection=http.client.HTTPConnection('127.0.0.1',PORT,timeout=3)
        try:
            connection.request('GET','/v1/delivery-estimate?product=camera&zone=city&quantity=1',headers={'X-Journey-ID':ATTEMPT})
            response=connection.getresponse()
            self.assertEqual(200,response.status)
            before=snapshot()['events']
            self.assertTrue(any(e['kind']=='headers' for e in before))
            self.assertFalse(any(e['kind']=='written' for e in before))
            self.assertEqual('available',json.loads(response.read())['status'])
        finally: connection.close()
    def test_09_related_schema_is_separate_from_critical_estimate(self):
        status,_,raw=request('/v1/related?product=camera',headers={'X-Journey-ID':ATTEMPT})
        self.assertEqual(200,status); self.assertEqual(['bag','headphones'],json.loads(raw)['productIds'])
        config(relatedHttp=503)
        self.assertEqual(503,request('/v1/related?product=camera',headers={'X-Journey-ID':ATTEMPT})[0])
        self.assertEqual(200,estimate()[0])

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--port',type=int,default=8878); parser.add_argument('--output',type=pathlib.Path,required=True)
    args=parser.parse_args(); PORT=args.port; args.output.mkdir(parents=True,exist_ok=True)
    log=args.output/'fixture-events.jsonl'; stderr=args.output/'fixture-process.txt'
    if log.exists() or stderr.exists(): raise SystemExit('Refusing to overwrite existing evidence')
    with stderr.open('w') as sink:
        process=subprocess.Popen([sys.executable,str(ROOT/'fixture.py'),'--port',str(PORT),'--log',str(log)],stdout=sink,stderr=sink)
        try:
            deadline=time.monotonic()+5
            while True:
                if process.poll() is not None: raise RuntimeError('Owned fixture exited before readiness')
                try:
                    if request('/admin/events')[0]==200:break
                except OSError: pass
                if time.monotonic()>deadline:raise RuntimeError('Fixture readiness timeout')
                time.sleep(.03)
            with (args.output/'unittest.txt').open('w') as stream:
                result=unittest.TextTestRunner(stream=stream,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(FixtureContractTest))
        finally:
            process.terminate(); process.wait(timeout=5)
    report={'passed':result.wasSuccessful(),'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped),
            'fixturePort':PORT,'scope':'Owned Python fixture over actual loopback HTTP; no Android or capacity/latency measurement',
            'sourceSha256':hashlib.sha256((ROOT/'fixture.py').read_bytes()).hexdigest(),
            'testSha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'stages':STAGES,'cleanup':{'ownedProcessExited':process.poll() is not None}}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='stages'})); sys.exit(0 if result.wasSuccessful() else 1)
