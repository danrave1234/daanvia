import os,pathlib,urllib.parse,subprocess,sys
root=pathlib.Path(__file__).resolve().parents[1]
p=root/'gradle.properties'; lines=[x for x in p.read_text().splitlines() if not x.startswith('systemProp.')]
for key,scheme in [('HTTPS_PROXY','https'),('HTTP_PROXY','http')]:
 u=urllib.parse.urlsplit(os.environ.get(key,''))
 if u.hostname:lines.extend(['systemProp.'+scheme+'.proxyHost='+u.hostname,'systemProp.'+scheme+'.proxyPort='+str(u.port or 80)])
p.write_text('\n'.join(lines)+'\n')
e=os.environ.copy();e['JAVA_HOME']='/tmp/daanvia-jdk21/usr/lib/jvm/java-21-openjdk-amd64'
raise SystemExit(subprocess.call(['/tmp/gradle-8.13/bin/gradle','--no-daemon','-p',str(root),'--console=plain']+sys.argv[1:],env=e))
