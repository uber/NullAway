import sys, os, subprocess, ConfigParser
from optparse import OptionParser

class2jar = dict()
jars_to_process = set()

parser = OptionParser()
parser.add_option("-v", "--verbose", action="store_true", dest="verbose", default=False, help="set verbosity")
parser.add_option("-c", "--config", dest="config", default=os.path.dirname(sys.argv[0])+"/android-jar.conf", help="configuration file path")
(options, args) = parser.parse_args()
if not os.path.isfile(options.config):
  sys.exit('Error! No configuration file at: '+options.config)
configParser = ConfigParser.SafeConfigParser()
configParser.read(options.config)
ajars_dir = configParser.get('android-paths', 'aosp-out-dir')
stubs_jar = configParser.get('android-paths', 'android-stubs-jar')
jarinfer = configParser.get('jar-infer-paths', 'jar-infer-path')
out_jar = configParser.get('android-model', 'android-model-jar')
wrk_dir = configParser.get('android-model', 'wrk-dir')
astubx_file = configParser.get('android-model', 'astubx-path')
package_name = configParser.get('android-model', 'package-name')
class_name = configParser.get('android-model', 'class-name')
dummy_code = configParser.get('android-model', 'dummy-code')

### Finding android libraries ###
print "> Finding android libraries..."
cmd = "find " + ajars_dir + " -name \"*.jar\""
ajars = subprocess.Popen(cmd, stdout=subprocess.PIPE, shell=True).stdout.read().splitlines()
for ajar in ajars:
  cmd = "jar tvf " + ajar + " | grep \"\.class$\" | awk '{print $8}'"
  ajar_classes = subprocess.Popen(cmd, stdout=subprocess.PIPE, shell=True).stdout.read().splitlines()
  for ajar_class in ajar_classes:
    if ajar_class in class2jar:
      if options.verbose:
        print "[Warn] Same class in multiple jars : " + ajar_class + " in " + class2jar[ajar_class] + " , " + ajar
    else:
      class2jar[ajar_class] = ajar
cmd = "jar tvf " + stubs_jar + " | grep \"\.class$\" | awk '{print $8}'"
ajar_classes = subprocess.Popen(cmd, stdout=subprocess.PIPE, shell=True).stdout.read().splitlines()
found = 0
for ajar_class in ajar_classes:
  if ajar_class in class2jar:
    found += 1
    jars_to_process.add(class2jar[ajar_class])
  else:
    if options.verbose:
      print "[Warn] Class not found in any jar : " + ajar_class
print ",".join(list(jars_to_process))
print "Found " + str(found) + " / " + str(len(ajar_classes)) + " in " + str(len(jars_to_process)) + " jars"

### Running jarinfer ###
print "> Running jarinfer on android jars..."
cmd = "mkdir -p "+wrk_dir+" && java -jar " + jarinfer + " -i " + ",".join(list(jars_to_process)) + " -o " + wrk_dir + "/" + astubx_file
if options.verbose:
  cmd += " -dv"
subprocess.call(cmd, shell=True)

### Writing models ###
print "> Writing jarinfer models for android jar..."
prefix = package_name.replace(".","/") + "/"
cmd = "cd "+wrk_dir+" && mkdir -p "+prefix+" && echo \""+dummy_code+"\" > "+prefix+class_name+".java && javac "+prefix+class_name+".java && jar cf "+out_jar+" "+prefix+class_name+".class "+astubx_file+" && jar tvf "+out_jar
subprocess.call(cmd, shell=True)
print "> Done! Android model jar: " + out_jar

### Uploading models ###
