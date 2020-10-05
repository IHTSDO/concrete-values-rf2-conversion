#!/bin/bash
set -e; # Stop on error

#Parameters expected to be made available from Jenkins
envPrefix="dev-"
#username=
#password=
branch=MAIN

ims_url=https://${envPrefix}ims.ihtsdotools.org
ts_url=http://${envPrefix}snowstorm.ihtsdotools.org

loginToIMS() {
	echo "Logging in as $username to $ims_url"
	curl -i -v --cookie-jar cookies.txt -H "Content-Type: application/json" -X POST ${ims_url}/api/authenticate --data '{"login":"'${username}'","password":"'${password}'","rememberMe":"false"}'
	echo "Cookies saved"
}

downloadDelta() {
curl -sSi 'https://dev-snowstorm.ihtsdotools.org/snowstorm/snomed-ct/exports' \
  -H 'Connection: keep-alive' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  --cookie cookie.txt \
  --data-binary $'{ "branchPath": "MAIN",  "type": "DELTA"\n}' | grep -oP 'Location: \K.*' >> location.txt
  
  #wget << location.txt
}


loginToIMS
downloadDelta
