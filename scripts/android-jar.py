import subprocess
from optparse import OptionParser

ajars_dir = "/Volumes/AOSP/out/target/common/obj/JAVA_LIBRARIES/framework_intermediates"
stubsjar = "~/android-sdk/platforms/android-27/android.jar"
nullaway_dir = "~/src/NullAway"
jarinfer = nullaway_dir + "/jar-infer-cli/build/libs/jar-infer-cli-0.4.8-SNAPSHOT.jar"
astubx_file = "META-INF/nullaway/jarinfer.astubx"

class2jar = dict()
jars_to_process = set()

parser = OptionParser()
parser.add_option("-v", "--verbose", action="store_true", dest="verbose", default=False)
(options, args) = parser.parse_args()

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
cmd = "jar tvf " + stubsjar + " | grep \"\.class$\" | awk '{print $8}'"
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
cmd = "java -jar " + jarinfer + " -i " + ",".join(list(jars_to_process)) + " -o " + nullaway_dir + "/" + astubx_file
subprocess.Popen(cmd, stdout=subprocess.PIPE, shell=True)

### Writing models ###
print "> Writing jarinfer models to android jar..."
cmd = "cd " + nullaway_dir + " && jar uf " + stubsjar + " " + astubx_file
subprocess.Popen(cmd, stdout=subprocess.PIPE, shell=True)
print "> Done. Annotated android jar: " + stubsjar
