import os, sys, datetime, time, subprocess
from optparse import OptionParser
repo_dir = "/Users/subarno/Uber/android"
times = dict()
total = 0.0
parser = OptionParser()
parser.add_option("-i", "--input", dest="times_file", default=os.path.dirname(sys.argv[0])+"/times.log", help="file containing build times stripped from buck output")
(options, args) = parser.parse_args()
if not os.path.isfile(options.times_file):
  sys.exit('Error! No build times log file at: '+options.times_file)
with open(options.times_file) as f:
  for line in f:
    line = line.rstrip('\n')
    p1 = line.find('JOBS ') + 5
    p2 = line.find('s //', p1)
    t = line[p1:p2]
    j = line[p2+2:]
    if "jar_infer_" in j:
      m = int(t[:t.find('m ')]) if t.find('m ') > -1 else 0
      s = float(t[t.find('m ')+2:]) if t.find('m ') > -1 else float(t)
      s = float(m*60) + s
      times[j] = s
for j in sorted(times, key=lambda k: times[k], reverse=True):
  total += times[j]
  print str(times[j]) + "s, " + j
print "total time: " + str(total)
