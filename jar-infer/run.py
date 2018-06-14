#!/usr/bin/env python

import subprocess
import sys

java_main_class = sys.argv[1]

java_app_args = ",".join(["'" + x + "'" for x in sys.argv[2:]])

gradle_cmd_args = [
    "gradle",
    "run",
    "-PmainClass=" + java_main_class,
    "-PappArgs=[" + java_app_args + "]"
    ]

print " ".join(gradle_cmd_args)

subprocess.call(gradle_cmd_args)



