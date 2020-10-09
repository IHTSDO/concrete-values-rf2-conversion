#!/bin/bash
set -e; # Stop on error
#set -x;

#Parameters expected to be made available from Jenkins
envPrefix="dev-"
#username=
#password=
branch=MAIN
effectiveDate=20210131
previousRelease=SnomedCT_InternationalRF2_PRODUCTION_20200731T120000Z.zip
productKey=concrete_domains_daily_build
export_category="UNPUBLISHED"
loadTermServerData=false
loadExternalRefsetData=false
converted_file_location=output
releaseCenter=international
branchPath=MAIN

s3BucketLocation="snomed-international/authoring/versioned-content/"
deltaArchiveFile="delta_archive.zip"
curlFlags="isS"
commonParams="--cookie-jar cookies.txt --cookie cookies.txt -${curlFlags} --retry 0"


ims_url=https://${envPrefix}ims.ihtsdotools.org
tsUrl=https://${envPrefix}snowstorm.ihtsdotools.org
release_url=https:///${envPrefix}release.ihtsdotools.org

loginToIMS() {
	echo "Logging in as $username to $ims_url"
	curl -i -v --cookie-jar cookies.txt -H 'Accept: application/json' -H "Content-Type: application/json" ${ims_url}/api/authenticate --data '{"login":"'${username}'","password":"'${password}'","rememberMe":"false"}'
	echo "Cookies saved"
}

downloadDelta() {
curl -sSiv ${tsUrl}/snowstorm/snomed-ct/exports \
  -H 'Connection: keep-alive' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  --cookie cookies.txt \
  --data-binary $'{ "branchPath": "MAIN",  "type": "DELTA"\n}' | grep -oP 'Location: \K.*' > location.txt
  
  delta_location=`head -1 location.txt | tr -d '\r'`
  echo "Recovering delta from $delta_location"
  wget --load-cookies cookies.txt ${delta_location}/archive -O ${deltaArchiveFile}
}

downloadPreviousRelease() {
	if [ -f "${previousRelease}" ]; then
		echo "Previous published release ${previousRelease} already present"
	else
	  echo "Downloading previous published release: ${previousRelease} from S3 ${s3BucketLocation}"
	  aws s3 cp s3://${s3BucketLocation}${previousRelease} ./
	fi
}

uploadInputFiles() {

		filesUploaded=0
		uploadUrl="${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/inputfiles"
		echo "Uploading input files from ${converted_file_location} to ${uploadUrl}"

		for file in `find . -type f -path "./${converted_file_location}/*" -name '*.txt'`;
		do
			echo "Upload Input File ${file}"
			curl ${commonParams} -F "file=@${file}" ${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/inputfiles | grep HTTP | ensureCorrectResponse
			filesUploaded=$((filesUploaded+1))
		done
		
		if [ ${filesUploaded} -lt 1 ] 
		then
			echo -e "Failed to find files to upload.\nScript halted."
			exit -1
		fi
}

callSrs() {

	echo "Deleting previous delta Input Files "
	curl ${commonParams} -X DELETE ${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/inputfiles/*.txt | grep HTTP | ensureCorrectResponse
	
	uploadInputFiles
	
	echo $product_key
	configJson="{\"effectiveDate\":\"${effectiveDate}\", \"exportCategory\":\"$export_category\", \"branchPath\":\"$branchPath\", \"termServerUrl\":\"$tsUrl\",\"loadTermServerData\":$loadTermServerData,\"loadExternalRefsetData\":$loadExternalRefsetData}"
	echo "JSON to post: $configJson"
	
	url="$release_url/api/v1/centers/${releaseCenter}/products/${productKey}/release" 
	echo "URL to post: $url"
	
	curl ${commonParams} -X POST $url -H "Content-Type: application/json" -d "$configJson" | grep HTTP | ensureCorrectResponse
	echo ""
	echo "Release build for product $product_key is started."
	echo "Please find the latest build result using the link below:"
	echo "$release_url/api/v1/centers/${releaseCenter}/products/${productKey}/builds/"
}

ensureCorrectResponse() {
	while read response 
	do
		httpResponseCode=`echo $response | grep "HTTP" | awk '{print $2}'`
		echo " Response received: $response "
		if [ "${httpResponseCode:0:1}" != "2" ] && [ "${httpResponseCode:0:1}" != "1" ]
		then
			echo -e "Failure detected with non-2xx HTTP response code received [${httpResponseCode}].\nExecution terminated."
			exit -1
		fi
	done
	echo
}

loginToIMS
downloadDelta
downloadPreviousRelease
mkdir -p ${converted_file_location}
rm -r ./${converted_file_location}/*
echo "Performing Concrete Domain Conversion..."
java -jar target/CdConversion.jar -s ${previousRelease} -d ${deltaArchiveFile}
callSrs

