#!/usr/bin/env python3
"""Guarded C27 appearance acquisition. Root alone executes device operations.

Use the optimized resilient APK. No app data is cleared. Stable product and
estimate states are distinct from bounded transient acquisition; captured pixels
require independent inspection and are not timing or full accessibility proof.
"""
from pathlib import Path
import argparse, hashlib, json, re, sys, time, urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'projects/commerce-app/tools'))
from capture_checkpoint_appearance import CaptureSession, area, bounds, matches, panes, pane_key, find_pane, signature
from fresh_android_xml import read_ui_xml

APP = 'staff.android.design'
PORT = 'tcp:8877'
BASE = dict(cityDelayMs=300, northDelayMs=300, relatedDelayMs=300, bodyDelayMs=0,
    estimateHttp=200, relatedHttp=200, estimateMode='available',
    validForMillis=60000, schema=1, feeMinorOverride=None)
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()

class DeliveryCapture(CaptureSession):
    def swipe(self, pane, direction):
        left, top, right, bottom = bounds(pane)
        if not area((left, top, right, bottom)):
            raise RuntimeError('Fresh pane is empty.')
        high, low = top + (bottom-top)*35//100, top + (bottom-top)*65//100
        start, end = (low, high) if direction == 'down' else (high, low)
        self.adb('shell', 'input', 'swipe', (left+right)//2, start, (left+right)//2, end, 180)
        return self.settled()

    def failure_witness(self):
        result = {'scope': 'Unsettled failure viewport before restoration; not acceptance.', 'errors': {}}
        for name, producer in [
            ('xml', lambda: read_ui_xml(self.adb, '/sdcard/common-goods-failure.xml', {APP, 'android', 'com.android.systemui'}, attempts=1).encode()),
            ('png', lambda: self.adb('exec-out', 'screencap', '-p')),
            ('window', lambda: self.adb('shell', 'dumpsys', 'window', 'windows')),
        ]:
            try:
                data = producer(); path = self.args.output / ('failure-viewport.' + ('txt' if name == 'window' else name))
                path.write_bytes(data); result[name] = path.name; result[name+'Sha256'] = sha(path)
            except Exception as error:
                result['errors'][name] = str(error)
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', '--output', dest='output', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--expected-avd', default='StaffAndroid_API_36')
    parser.add_argument('--candidate', default='resilient-release')
    parser.add_argument('--expected-version', type=int, default=2702)
    parser.add_argument('--configs', nargs='+', choices=['light', 'dark', 'large', 'wide'], default=['light', 'dark', 'large', 'wide'])
    parser.add_argument('--max-scrolls', type=int, default=18)
    parser.add_argument('--settle-timeout', type=float, default=35)
    parser.add_argument('--skip-transient', action='store_true', help='Explicitly omit bounded optional-loading acquisition; stable states remain required.')
    args = parser.parse_args(); args.package = APP
    if not 1 <= args.max_scrolls <= 24 or not 1 <= args.settle_timeout <= 120:
        parser.error('Use bounded scroll/settle limits.')
    apk = args.apk.resolve()
    if not apk.is_file(): parser.error('APK does not exist.')
    if args.output.exists(): parser.error('Use a new evidence directory; retain prior attempts.')
    args.output.mkdir(parents=True)
    source = Path(__file__).read_bytes(); (args.output/'capture-script.py').write_bytes(source)
    dependencies = [ROOT/'projects/commerce-app/tools/capture_checkpoint_appearance.py', ROOT/'projects/catalog-network/tools/fresh_android_xml.py']
    report = {'status':'STARTING', 'package':APP, 'serial':args.serial, 'candidate':args.candidate,
        'apk':str(apk), 'apkSha256':sha(apk), 'expectedVersionCode':args.expected_version,
        'captureScriptSha256':hashlib.sha256(source).hexdigest(),
        'captureDependencies':{str(p.relative_to(ROOT)):sha(p) for p in dependencies},
        'rows':[], 'fixtureActions':[], 'restorationErrors':[],
        'scope':'Optimized resilient Common Goods Shop, Camera and delivery options in four display configurations. Stable state screenshots and optional-loading attempts are separate from correctness and timing. No app data clear; no critical-loading capture claim.'}
    session = DeliveryCapture(args)
    original = original_reverse = original_fixture = None
    failure = None

    def save(): (args.output/'result.json').write_text(json.dumps(report, indent=2)+'\n')
    def admin(path, payload=None):
        request = urllib.request.Request('http://127.0.0.1:8877'+path,
            data=None if payload is None else json.dumps(payload).encode(), headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request, timeout=10) as response: value = json.load(response)
        report['fixtureActions'].append({'path':path, 'payload':payload, 'hostMonotonicNanos':time.monotonic_ns()})
        return value
    def configure(**changes):
        expected = dict(BASE, **changes)
        actual = admin('/admin/config', expected)
        if actual != expected: raise RuntimeError('Fixture did not apply the exact requested configuration.')
        return actual
    def snapshot(name):
        value = admin('/admin/events'); path = args.output/(name+'-fixture.json')
        path.write_text(json.dumps(value, indent=2)+'\n')
        return {'path':path.name, 'sha256':sha(path), 'control':value['control'], 'events':len(value['events'])}
    def has(raw, label):
        return any(n.get('package') == APP and matches(n, label) for n in ET.fromstring(raw).iter('node'))
    def framed(raw, labels):
        tree = ET.fromstring(raw); parents = {c:p for p in tree.iter() for c in p}
        for label in labels:
            found = False
            for node in tree.iter('node'):
                if node.get('package') != APP or not matches(node, label): continue
                left, top, right, bottom = bounds(node)
                if not area((left,top,right,bottom)): continue
                valid = True; parent = node
                while parent in parents:
                    parent = parents[parent]
                    if parent.get('scrollable') == 'true':
                        pl,pt,pr,pb = bounds(parent)
                        if left < pl or right > pr or top < pt+12 or bottom > pb-12: valid = False
                if valid: found = True; break
            if not found: return False
        return True
    def reveal(raw, labels, required=True):
        labels = [labels] if isinstance(labels,str) else labels
        if framed(raw,labels): return raw
        for key in [pane_key(p) for p in panes(ET.fromstring(raw),APP)]:
            for direction in ['down','up']:
                for _ in range(args.max_scrolls):
                    pane = find_pane(ET.fromstring(raw),APP,key)
                    if pane is None: break
                    before = signature(pane); raw = session.swipe(pane,direction)
                    if framed(raw,labels): return raw
                    after = find_pane(ET.fromstring(raw),APP,key)
                    if after is None or signature(after) == before: break
        if required: raise RuntimeError('Could not fully frame '+repr(labels))
        return raw
    def tap(label, raw, settle=True):
        raw = reveal(raw,label); raw = session.xml()
        target = session.target(ET.fromstring(raw),label)
        if target is None: raise RuntimeError('Fresh guarded enabled target missing: '+label)
        left,top,right,bottom = bounds(target)
        session.adb('shell','input','tap',(left+right)//2,(top+bottom)//2)
        return session.settled() if settle else session.xml()
    def capture(row,name,raw,condition):
        frame = session.capture(row['config']+'-'+name,raw)
        frame.update(condition=condition,surfacePackage=APP,fixtureSnapshot=snapshot(row['config']+'-'+name))
        row['frames'].append(frame); save(); return raw
    def price(raw,tag):
        raw = reveal(raw,tag)
        values = [n.get('text','') for n in ET.fromstring(raw).iter('node') if matches(n,tag)]
        if len(values)!=1 or re.sub(r'\D','',values[0])!='12999':
            raise RuntimeError('Unexpected displayed product price: '+repr(values))
        return raw
    def scope(raw,tag,expected):
        values = [n.get('text','') for n in ET.fromstring(raw).iter('node') if matches(n,tag)]
        if values != [expected]: raise RuntimeError('Unexpected selected/accepted scope: '+repr(values))
    def estimate(row,name,raw,phase,condition):
        # The status and final description bound the full card without assuming
        # linear Android font scaling or making price/control fit one viewport.
        endings = {'idle':'Choose your area and quantity, then check delivery.',
            'ready':'An estimate, not a reservation. Stock and final terms are confirmed when you order.',
            'unserviceable':'Try another area or a smaller quantity. Your product details are still here.',
            'deadline':'The estimate took too long. Your product details are still here; you can try again.',
            'unavailable':"We couldn't get a delivery estimate. Please try again.",
            'expired':'Check again for a current estimate for your area and quantity.'}
        raw = reveal(raw,['estimate_'+phase,endings[phase]])
        return capture(row,name,raw,condition)
    def submit(raw):
        raw = tap('check_delivery',raw)
        return session.settled()
    def transient(row,raw):
        if args.skip_transient:
            row['optionalLoadingAcquisition'] = {'observed':False,'reason':'Explicit --skip-transient'}; save(); return raw
        attempts = []
        for number in range(1,3):
            configure(relatedDelayMs=6000)
            # Try to place the trigger and optional heading together first.
            # A miss remains an acquisition record, never a Loading claim.
            raw = reveal(raw,['check_delivery','Good together.'],required=False)
            raw = reveal(raw,'check_delivery')
            before = snapshot(row['config']+f'-optional-attempt{number}-before')
            raw = tap('check_delivery',raw,settle=False)
            if not framed(raw,['related_loading']):
                ps = panes(ET.fromstring(raw),APP)
                if ps:
                    left,top,right,bottom = bounds(ps[0])
                    session.adb('shell','input','swipe',(left+right)//2,top+(bottom-top)*4//5,(left+right)//2,top+(bottom-top)//5,150)
                    raw = session.xml()
            observed = framed(raw,['related_loading'])
            name = row['config']+f'-optional-attempt{number}'
            if observed:
                raw = capture(row,'optional-loading',raw,'Fresh in-flight optional recommendation progress. Fixture delay exceeds its separate 3000 ms budget; this screenshot is not a timing measurement.')
                attempts.append({'attempt':number,'observedInXml':True,'before':before,'acceptedFrame':row['config']+'-optional-loading'})
                row['optionalLoadingAcquisition']={'observed':True,'attempts':attempts}; save()
                return session.settled()
            missed = session.capture(name+'-missed',raw)
            missed.update(scope='Transient not observed; diagnostic acquisition witness excluded from accepted frames.',before=before,after=snapshot(name+'-after'))
            attempts.append(missed)
            raw = session.settled()
        row['optionalLoadingAcquisition']={'observed':False,'attempts':attempts,
            'reason':'The bounded optional state ended or was not fully framed before fresh XML/PNG acquisition.'}; save()
        return raw

    try:
        avd = session.adb('emu','avd','name').decode().strip()
        if args.expected_avd not in avd.splitlines(): raise RuntimeError('Unexpected emulator: '+avd)
        report['avd']=avd; original=session.settings(); report['originalSettings']=original
        original_reverse=session.adb('reverse','--list').decode(); report['originalReverse']=original_reverse
        mappings=[p for p in map(str.split,original_reverse.splitlines()) if len(p)>=3 and p[-2]==PORT]
        if len(mappings)>1: raise RuntimeError('Ambiguous original chapter reverse mapping.')
        original_fixture=admin('/admin/events')['control']; report['originalFixtureState']=original_fixture
        session.adb('reverse',PORT,PORT); session.adb('install','-r',apk)
        installed=session.adb('shell','pm','path',APP).decode().strip().removeprefix('package:')
        session.adb('pull',installed,args.output/'installed.apk')
        if sha(args.output/'installed.apk')!=report['apkSha256']: raise RuntimeError('Installed APK mismatch.')
        package_dump=session.adb('shell','dumpsys','package',APP)
        (args.output/'installed-package.txt').write_bytes(package_dump)
        report['installedPackageSha256']=hashlib.sha256(package_dump).hexdigest()
        if not re.search(rb'versionCode='+str(args.expected_version).encode()+rb'\b',package_dump):
            raise RuntimeError('Expected optimized resilient versionCode not installed.')
        for config in args.configs:
            session.adb('shell','am','force-stop',APP)
            session.adb('shell','wm','size','1920x1200' if config=='wide' else 'reset')
            session.adb('shell','wm','density','240' if config=='wide' else 'reset')
            session.adb('shell','settings','put','system','font_scale','2.0' if config=='large' else '1.0')
            session.adb('shell','cmd','uimode','night','yes' if config=='dark' else 'no')
            row={'config':config,'settings':session.settings(),'status':'STARTING','frames':[]};report['rows'].append(row)
            assert float(row['settings']['font'])==(2 if config=='large' else 1)
            assert 'Night mode: '+('yes' if config=='dark' else 'no') in row['settings']['night']
            if config=='wide': assert 'Override size: 1920x1200' in row['settings']['size'] and 'Override density: 240' in row['settings']['density']
            else: assert 'Override' not in row['settings']['size'] and 'Override' not in row['settings']['density']
            configure(); row['startFixture']=snapshot(config+'-start')
            session.adb('shell','am','start','-W','-n',APP+'/.MainActivity','-f','0x10008000')
            raw=session.settled(exact=['shop']);raw=capture(row,'shop-top',raw,'Common Goods Shop and real Camera entry.')
            raw=tap('explore_camera',raw);raw=session.settled(exact=['product_detail'])
            raw=capture(row,'camera-top',raw,'Camera photo, identity and product detail layout.')
            raw=price(raw,'product_price');raw=capture(row,'camera-price',raw,'Fully framed ₹12,999 product price; product action is separately framed when necessary.')
            raw=reveal(raw,'open_delivery');raw=capture(row,'camera-action',raw,'Complete Delivery options control reached through the real product journey.')
            raw=tap('open_delivery',raw);raw=session.settled(exact=['delivery_screen'])
            raw=price(raw,'delivery_product_price');raw=capture(row,'delivery-product',raw,'Cached Camera and full ₹12,999 remain useful on the delivery screen.')
            if config == 'large':
                # These are separate honest viewports. At enlarged text the
                # controls, submitted scope and action need not fit together.
                raw=reveal(raw,['zone_city','zone_north'])
                raw=capture(row,'city-destination',raw,'Both synthetic destination controls fully framed; City centre selected. Quantity and action are captured separately.')
                raw=reveal(raw,['quantity_1','quantity_2','quantity_3'])
                raw=capture(row,'city-quantity',raw,'All three quantity controls fully framed; quantity1 selected. Destination and action are separate viewports.')
                raw=reveal(raw,'selected_scope');scope(raw,'selected_scope','1 Camera · City centre')
                raw=capture(row,'city-selection',raw,'The complete selected scope is 1 Camera · City centre; separate frames retain all input controls and the action.')
                raw=reveal(raw,'check_delivery')
                raw=capture(row,'city-action',raw,'Complete Check delivery control at enlarged text, reached by guarded scrolling.')
            else:
                raw=reveal(raw,['zone_city','zone_north','quantity_1','quantity_3','selected_scope','check_delivery'])
                scope(raw,'selected_scope','1 Camera · City centre')
                raw=capture(row,'city-selection',raw,'Synthetic City centre and quantity1, with the full Check delivery action.')
            raw=estimate(row,'idle-estimate',raw,'idle','Designed idle estimate card explains the next action.')
            raw=submit(raw);raw=estimate(row,'city-ready',raw,'ready','City estimate arrives independently of optional recommendations.')
            scope(raw,'estimate_scope','1 Camera · City centre')
            raw=reveal(raw,['related_bag','related_headphones','back_camera']);raw=capture(row,'related-ready',raw,'Related products and complete Back to Camera control below the independent estimate.')
            raw=transient(row,raw)
            configure();raw=tap('zone_north',raw);raw=tap('quantity_2',raw)
            raw=reveal(raw,['selected_scope','check_delivery']);scope(raw,'selected_scope','2 Cameras · North district')
            raw=capture(row,'north-selection',raw,'Changed destination and quantity clear the old estimate; new scope is explicit.')
            raw=submit(raw);raw=estimate(row,'north-ready',raw,'ready','North district quantity2 estimate uses its own fee and arrival window.')
            scope(raw,'estimate_scope','2 Cameras · North district')
            configure(estimateMode='unserviceable');raw=submit(raw)
            raw=estimate(row,'unserviceable',raw,'unserviceable','Server cannot offer delivery; product details and selection remain available.')
            configure(northDelayMs=2500);raw=submit(raw)
            raw=estimate(row,'deadline',raw,'deadline','Essential end-to-end deadline gives a designed terminal result and retry guidance.')
            configure(estimateHttp=503);raw=submit(raw)
            raw=estimate(row,'unavailable',raw,'unavailable','Unavailable estimate service has clear retry copy.')
            configure(relatedHttp=503);raw=submit(raw)
            raw=estimate(row,'ready-optional-failed',raw,'ready','A successful essential estimate survives the optional recommendation failure.')
            raw=reveal(raw,['related_unavailable','back_camera']);raw=capture(row,'related-unavailable',raw,'Optional failure is labeled separately with Back to Camera still fully visible.')
            configure(validForMillis=2500);raw=submit(raw)
            raw=estimate(row,'expired',raw,'expired','An expired estimate no longer displays itself as a current offer.')
            configure();raw=submit(raw);raw=estimate(row,'recovered',raw,'ready','Explicit Check again recovers a fresh estimate for the selected North district quantity2.')
            if config == 'light':
                configure(feeMinorOverride=9901);raw=submit(raw)
                raw=estimate(row,'fractional-fee',raw,'ready','A 9,901-minor-unit fee is visibly ₹99.01, preserving the authoritative fractional amount.')
                fees=[n.get('text','') for n in ET.fromstring(raw).iter('node') if matches(n,'delivery_fee')]
                if len(fees)!=1 or '₹99.01' not in fees[0]:
                    raise RuntimeError('Fractional fee did not preserve paise: '+repr(fees))
            row['endFixture']=snapshot(config+'-end');row['status']='CAPTURED_NOT_REVIEWED';save();print(config+' captured',flush=True)
        report['status']='CAPTURED_NOT_REVIEWED'
    except Exception as error:
        failure=error;report['status']='FAILED';report['error']=str(error)
        try: report['failureWitness']=session.failure_witness()
        except Exception as witness_error: report['failureWitness']={'error':str(witness_error)}
    finally:
        restore=[('shell','am','force-stop',APP)]
        if original:
            restore.append(('shell','settings','delete','system','font_scale') if original['font'] in ('null','') else ('shell','settings','put','system','font_scale',original['font']))
            restore.append(('shell','cmd','uimode','night',original['night'].split(':')[-1].strip()))
            for kind in ['size','density']:
                found=re.search(r'Override '+kind+r':\s*(\S+)',original[kind],re.I)
                restore.append(('shell','wm',kind,found.group(1) if found else 'reset'))
        for command in restore:
            try: session.adb(*command)
            except Exception as error: report['restorationErrors'].append(str(error))
        if original_reverse is not None:
            try:
                current=session.adb('reverse','--list').decode()
                if any(len(p)>=3 and p[-2]==PORT for p in map(str.split,current.splitlines())):session.adb('reverse','--remove',PORT)
                for parts in map(str.split,original_reverse.splitlines()):
                    if len(parts)>=3 and parts[-2]==PORT:session.adb('reverse',parts[-2],parts[-1])
                report['restoredReverse']=session.adb('reverse','--list').decode()
                if sorted(report['restoredReverse'].splitlines())!=sorted(original_reverse.splitlines()):report['restorationErrors'].append('Reverse mappings differ.')
            except Exception as error:report['restorationErrors'].append(str(error))
        if original_fixture is not None:
            try:
                report['restoredFixtureState']=admin('/admin/config',original_fixture)
                if report['restoredFixtureState']!=original_fixture:report['restorationErrors'].append('Fixture controls differ.')
            except Exception as error:report['restorationErrors'].append(str(error))
        try:
            report['restoredSettings']=session.settings()
            if original and report['restoredSettings']!=original:report['restorationErrors'].append('Display settings differ.')
        except Exception as error:report['restorationErrors'].append(str(error))
        if report['restorationErrors'] and not failure:report['status']='CAPTURED_RESTORE_FAILED'
        save();(args.output/'commands.json').write_text(json.dumps(session.commands,indent=2)+'\n')
        (args.output/'tap-guards.json').write_text(json.dumps(session.tap_guards,indent=2)+'\n')
    if failure:raise failure
    if report['restorationErrors']:raise RuntimeError('Restoration errors; inspect result.json.')


if __name__ == '__main__': main()
