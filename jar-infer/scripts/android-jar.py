import sys, os, subprocess, ConfigParser
from optparse import OptionParser

class2jar = dict()
jars_to_process = set()

opt_parser = OptionParser()
opt_parser.add_option("-v", "--verbose", action="store_true", dest="verbose", default=False, help="set verbosity")
opt_parser.add_option("-c", "--config", dest="config", default=os.path.dirname(sys.argv[0])+"/android-jar.conf", help="configuration file path")
(options, args) = opt_parser.parse_args()
if not os.path.isfile(options.config):
  sys.exit('Error! No configuration file at: '+options.config)
config_parser = ConfigParser.SafeConfigParser()
config_parser.read(options.config)
ajars_dir = config_parser.get('android-paths', 'aosp-out-dir')
stubs_jar = config_parser.get('android-paths', 'android-stubs-jar')
jarinfer = config_parser.get('jar-infer-paths', 'jar-infer-path')
wrk_dir = config_parser.get('android-model', 'wrk-dir')
astubx_file = config_parser.get('android-model', 'astubx-path')

### Finding android libraries ###
print "> Finding android libraries..."
# Since SDK 31, the directory structure mixes classes.jar and classes-header.jar files, we use
# only the former, as classes-header.jar files are missing methods' bytecode.
cmd = "find " + ajars_dir + " -name \"*.jar\" | grep -v classes-header.jar"
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
cmd = "java -jar " + jarinfer + " -i " + ",".join(list(jars_to_process)) + " -o " + wrk_dir + "/" + astubx_file
if options.verbose:
  cmd += " -dv"
print cmd
returncode = subprocess.call(cmd, shell=True)
sys.exit(returncode)
