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
source=terminology-server
#branchPath=MAIN

s3BucketLocation="snomed-international/authoring/versioned-content/"
deltaArchiveFile="delta_archive.zip"
classifiedArchiveFile="classified_archive.zip"
curlFlags="isS"
commonParams="--cookie-jar cookies.txt --cookie cookies.txt -${curlFlags} --retry 0"

ims_url=https://${envPrefix}ims.ihtsdotools.org
tsUrl=https://${envPrefix}snowstorm.ihtsdotools.org
release_url=https:///${envPrefix}release.ihtsdotools.org
classifyUrl=https:///${envPrefix}classification.ihtsdotools.org

loginToIMS() {
	echo "Logging in as $username to $ims_url"
	curl --cookie-jar cookies.txt -H 'Accept: application/json' -H "Content-Type: application/json" ${ims_url}/api/authenticate --data '{"login":"'${username}'","password":"'${password}'","rememberMe":"false"}'
	echo "Cookies saved"
}

downloadDelta() {
curl -sSi ${tsUrl}/snowstorm/snomed-ct/exports \
  -H 'Connection: keep-alive' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  --cookie cookies.txt \
  --data-binary $'{ "branchPath": "MAIN",  "type": "DELTA"}' | grep -oP 'Location: \K.*' > location.txt

	deltaLocation=`head -1 location.txt | tr -d '\r'`
	#Temp workaround for INFRA-1489
	deltaLocation="${deltaLocation//http:\//https:\/\/}"
	echo "Recovering delta from $deltaLocation"
	wget -q --load-cookies cookies.txt ${deltaLocation}/archive -O ${deltaArchiveFile}
}

classify() {
  set -e;
  echo "Zipping up the converted files"
  convertedArchive="convertedArchive.zip"
  zip ${convertedArchive} ./${converted_file_location}/*.txt

	echo "Calling classification"
	curl -sSi ${classifyUrl}/classification-service/classifications \
		--cookie cookies.txt \
		-H 'Connection: keep-alive' \
	  -F "previousPackage=${previousRelease}" \
	  -F "rf2Delta=@${convertedArchive}" | grep -oP 'Location: \K.*' > classification.txt

	classificationLocation=`head -1 classification.txt | tr -d '\r'` || echo 'Failed to recover classification identifier'
	echo "Classification location: $classificationLocation"
	set -x;
	output=
	count=0
	until [[ $output =~ COMPLETED ]]; do
		output=$(checkClassificationStatus 2>&1)
		echo "checked received: $output"

		((++count))
		echo "Checking response"

		if [[ $output =~ FAILED ]]; then
			echo "Classification reported failure"
			exit -1
		elif (( count > 20 )); then
			echo "Classification took more than 20 minutes - giving up"
			exit -1
		elif [[ $output =~ RUNNING ]]; then
			sleep 60
		fi
	done

	echo "Classification successful.  Recovering results from $classificationLocation"
	wget -q --load-cookies cookies.txt ${classificationLocation}/results/rf2 -O ${classifiedArchiveFile}
}

checkClassificationStatus() {
	curl -sS ${classificationLocation} \
		--cookie cookies.txt
}


downloadPreviousRelease() {
	if [ -f "${previousRelease}" ]; then
		echo "Previous published release ${previousRelease} already present"
	else
		echo "Downloading previous published release: ${previousRelease} from S3 ${s3BucketLocation}"
		aws s3 cp --no-progress s3://${s3BucketLocation}${previousRelease} ./
	fi
}

uploadSourceFiles() {
	today=`date +'%Y%m%d'`
	echo "Renaming rf2 files to target effective date: $effectiveDate"
	for file in `find . -type f -path "./${converted_file_location}/*" -name '*.txt'`;
	do
		mv -- "$file" "${file//${today}/${effectiveDate}}"
	done

	filesUploaded=0
	uploadUrl="${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/sourcefiles/${source}"
	echo "Uploading input files from ${converted_file_location} to ${uploadUrl}"
	for file in `find . -type f -path "./${converted_file_location}/*" -name '*.txt'`;
	do
		echo "Upload Source File ${file}"
		curl ${commonParams} -F "file=@${file}" ${uploadUrl} | grep HTTP | ensureCorrectResponse
		filesUploaded=$((filesUploaded+1))
	done

	if [ ${filesUploaded} -lt 1 ]
	then
		echo -e "Failed to find files to upload.\nScript halted."
		exit -1
	fi
}

uploadInputFiles() {
	filesUploaded=0
	uploadUrl="${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/inputfiles"

	echo "Renaming sct2 and der2 files to rel2 "
	for file in `find . -type f -path "./${converted_file_location}/*" -name '*.txt'`;
	do
		if [[ $file =~ "sct2" ]]; then
			mv -- "$file" "${file//sct2/rel2}"
		elif [[ $file =~ "der2" ]]; then
			mv -- "$file" "${file//der2/rel2}"
		fi
	done

	today=`date +'%Y%m%d'`
	echo "Renaming rel2 files to target effective date: $effectiveDate"
	for file in `find . -type f -path "./${converted_file_location}/*" -name '*.txt'`;
	do
		mv -- "$file" "${file//${today}/${effectiveDate}}"
	done

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
   echo "Deleting previous delta source files from: $source "
   curl ${commonParams} -X DELETE ${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/sourcefiles/${source} | grep HTTP | ensureCorrectResponse

	echo "Deleting previous delta Input Files "
	curl ${commonParams} -X DELETE ${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/inputfiles/*.txt | grep HTTP | ensureCorrectResponse

	uploadSourceFiles

	echo "Preparing configuration for product: $product_key"

	url="$release_url/api/v1/centers/${releaseCenter}/products/${productKey}/inputfiles/prepare"
	echo "Calling 'Prepare Files' with: $url"
	curl ${commonParams} -X POST $url -H "Content-Type: application/json" -d "$configJson" | grep HTTP | ensureCorrectResponse

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
rm -r ./${converted_file_location}/* || true
echo "Performing Concrete Domain Conversion..."
java -jar target/CdConversion.jar -s ${previousRelease} -d ${deltaArchiveFile}
classify
callSrs
