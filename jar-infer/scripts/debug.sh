#!/usr/bin/env bash
PRINT=true

# Counts of each summary
declare -a TAGS
declare -A COUNTS
declare -A UCOUNTS
declare -A LCOUNTS

print_header() {
  echo "$(printf '=%.0s' {1..5}) $1 $(printf '=%.0s' {1..50})" | cut -c1-50
}

summary() {
  TAG=$1
  TAGS+=("${TAG}")
  PATTERN=$2
  UNIQUE=${3:-false}
  EXCLUDE=${4:-""}
  HASLIST=${5:-false}
  if [[ -z $EXCLUDE ]]; then
    OUT=`less ${XLOG} | grep "${PATTERN}"`
  else
    OUT=`less ${XLOG} | grep "${PATTERN}" | grep -v "${EXCLUDE}"`
  fi
  COUNTS[${TAG}]=$(echo "${OUT}" | sed '/^\s*$/d' | wc -l | tr -d '[:space:]')
  if ${UNIQUE}; then
    OUT=`echo "${OUT}" | sort -u`
    UCOUNTS[${TAG}]=$(echo "${OUT}" | sed '/^\s*$/d' | wc -l | tr -d '[:space:]')
  fi
  if ${HASLIST}; then
    LCOUNT=`echo "${OUT}" | awk -F'[][]' -v fld=3 '{n += gsub(/,/,"",$fld)+1} END{print n};'`
    LCOUNTS[${TAG}]=${LCOUNT}
  fi
  if ${PRINT} && [[ ${COUNTS[${TAG}]} -gt 0 ]]; then
    print_header "${TAG}"
    echo "${OUT}"
  fi
}

print_summary_counts() {
  for T in "${TAGS[@]}" ; do
    echo -e "# ${T} = \t ${COUNTS[$T]} \t ${UCOUNTS[$T]} \t ${LCOUNTS[$T]}"
  done
}

### Main Script ###

# Get path of log file
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
LOG=$(grealpath $1)
if [ ! -f ${LOG} ]; then
  echo "Log not found! path:${LOG}"
  exit 1
fi

# Extract jar-infer prints
XLOG=${LOG}.ji
less $LOG | grep "\[JI [[:alpha:]]*\]" | awk -F "[[]JI " '{print $2}' > ${XLOG}

# Canned summary tasks
# Stats
summary "Analyzed Libraries" "opening library: " true
summary "Analyzed Classes" "analyzing class: " true
summary "Analyzed Methods" "analyzed method: " true
summary "Processed JARs" "processed jar to:" true
summary "Processed AARs" "processed aar to:" true
summary "Inferred arg annotations" "Inferred Nonnull param for method: " true "\[\]" true
summary "Inferred return annotations" "Inferred Nullable return for method: " true
summary "Loaded Classes" "Found source of class: " true
summary "Loaded arg annotations" ", arg annotation: " true
summary "Loaded return annotations" ", return annotation: " true
summary "Used @Nonnull params" "Nonnull params: \[" true "" true
summary "Used @Nullable returns" "Nullable return for method: " true

# Handler Errors
summary "Class source unresolved !" "Cannot resolve source for class:" true
summary "No astubx in jar !" "Cannot find jarinfer.astubx in jar:" true
summary "Couldn't open astubx !" "Cannot load jarinfer.astubx in jar:" true
summary "No Annotation Cache entry for Class !" "Cannot find Annotation Cache for class:" true

# Custom summary tasks
### List methods for which can't inferred or empty arg annotations
print_header "No Annotation Cache entry for Method !"
less $XLOG | grep "Inferred Nonnull param for method: " > $DIR/tmp_methods.log
less $XLOG | grep "Cannot find Annotation Cache entry for method:" | grep -v "()" | sort -u > $DIR/tmp_notfound.log
while read line; do
  mtd=$(echo $line | awk -F "Cannot find Annotation Cache entry for method:" '{print $2}' | awk -F " in class:" '{print $1}')
  fnd=$(less $DIR/tmp_methods.log | grep "$mtd")
  if [[ $(echo $fnd | sed '/^\s*$/d' | wc -l) -lt 1 ]]; then
    echo "method not found: $mtd"
  else
    echo $fnd | grep -v "\[\]"
  fi
done < $DIR/tmp_notfound.log
rm -rf $DIR/tmp_methods.log $DIR/tmp_notfound.log

: <<'COMMENT'
### Print the mapping of duplicated classes to source jars
declare -A NINJARS
less $XLOG | grep "DEBUG\] jar-infer called for cldr: " > $DIR/tmp.log
while read line; do
  cls=$(echo $line | awk -F ", cls: " '{print $2}' | awk -F ", lib: " '{print $1}')
  lib=$(echo $line | awk -F ", lib: " '{print $2}')
  NINJARS[${cls}]=$((${NINJARS[${cls}]} + 1))
done < $DIR/tmp.log
for C in "${NINJARS[@]}" ; do
  echo -e "${NINJARS[$C]} ${C}" >> $DIR/freq.log
done
#while read line; do
#  cls=$(echo line | cut -d' ' -f2)
#  libs=$(less $DIR/tmp.log | grep ", cls: ${cls}" | awk -F ", lib: " '{print $2}')
#  echo -e "$line\n$libs"
#done < $DIR/freq.log
#rm -rf $DIR/tmp.log
#rm -rf $DIR/freq.log
COMMENT

###
print_header "Nullaway errors !"
less $LOG | grep -A 10 "warning: \[NullAway\]"

# Epilogue
print_header "SUMMARY"
print_summary_counts
#rm -rf $XLOG
tail -n 4 $LOG
