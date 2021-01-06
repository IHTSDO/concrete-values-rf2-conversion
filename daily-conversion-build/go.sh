#!/bin/bash
set -e; # Stop on error
#set -x;

#Parameters expected to be made available from Jenkins
envPrefix="dev-"
#username=
#password=
#branchPath=MAIN
#loadExternalRefsetData=true
#previousPackage=prod_main_2021-01-31_20201124120000.zip

if [ -z "${branchPath}" ]; then
	echo "Environmental variable 'branchPath' has not been specified.  Unable to continue"
	exit -1
fi

if [ -z "${branchPath}" ]; then
	echo "Environmental variable 'loadExternalRefsetData' has not been specified.  Unable to continue"
	exit -1
fi

if [ -z "${previousPackage}" ]; then
	echo "Environmental variable 'previousPackage' has not been specified.  Unable to continue"
	exit -1
fi

effectiveDate=20210131
productKey=concrete_domains_daily_build
export_category="UNPUBLISHED"
loadTermServerData=false
converted_file_location=output
releaseCenter=international
source=terminology-server

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
	echo "Initiating Delta export against ${branchPath}"
	curl -sSi ${tsUrl}/snowstorm/snomed-ct/exports \
  -H 'Connection: keep-alive' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  --cookie cookies.txt \
  --data-binary $'{ "branchPath": "'$branchPath'",  "type": "DELTA"}' | grep -oP 'Location: \K.*' > location.txt || echo "Failed to obtain delta file"

	deltaLocation=`head -1 location.txt | tr -d '\r'`

	if [ -z "${deltaLocation}" ]; then
	   exit -1
	fi

	#Temp workaround for INFRA-1489
	deltaLocation="${deltaLocation//http:\//https:\/\/}"
	echo "Recovering delta from $deltaLocation"
	wget -q --load-cookies cookies.txt ${deltaLocation}/archive -O ${deltaArchiveFile}
}

classify() {
  echo "Zipping up the converted files"
  convertedArchive="convertedArchive.zip"
  rm ${convertedArchive} || true
  zip -r ${convertedArchive} ${converted_file_location}
  
  echo "Zipping up the converted files needed for classification"
  convertedClassificationArchive="convertedClassificationArchive.zip"
  rm ${convertedClassificationArchive} || true
	shopt -s globstar
  zip  ${convertedClassificationArchive} ${converted_file_location}/**/*Concept* ${converted_file_location}/**/*_Relationship* ${converted_file_location}/**/*OWL* ${converted_file_location}/**/*_cissccRefset_MRCMAttributeDomain*

	echo "Calling classification"
	curl -sSi ${classifyUrl}/classification-service/classifications \
		--cookie cookies.txt \
		-H 'Connection: keep-alive' \
	  -F "previousPackage=${previousPackage}" \
	  -F "rf2Delta=@${convertedClassificationArchive}" | ( grep -oP 'Location: \K.*' || true ) > classification.txt
	
	classificationLocation=`head -1 classification.txt | tr -d '\r'`
	
	if [ -z "${classificationLocation}" ]; then
		echo "Failed to recover classification identifier"
		exit -2
	fi
	
	
	echo "Classification location: $classificationLocation"
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

applyClassificationChanges() {
	classificationOutputDir="classification_output"
	mkdir ${classificationOutputDir} || true
	rm ${classificationOutputDir}/*.txt || true
	unzip -j -o ${classifiedArchiveFile} -d  ${classificationOutputDir}

	#We know the names of the files to append first the relationship delta
	sourceFile=$(find ${classificationOutputDir}/*Relationship_Delta*)
	targetFile=$(find ${converted_file_location} -name *_Relationship_Delta*)

	javaCmd="java -cp target/CdConversion.jar org.snomed.otf.cd.ApplyClassificationDelta ${sourceFile} ${targetFile}"
	echo "Appending ${sourceFile} to ${targetFile} using ${javaCmd}"
	${javaCmd}

	#Now are we also appending the concrete values file, or does it not exist yet?
	sourceFile=$(find ${classificationOutputDir}/*RelationshipConcreteValues_Delta*)
	sourceFileRenamed=$(basename $sourceFile)
	sourceFileRenamed=${sourceFileRenamed//Classification/INT}
	targetFile=$(find ${converted_file_location} -name *RelationshipConcreteValues_Delta*)

	if [ -z "${targetFile}" ]; then
		newLocation="${converted_file_location}/SnomedCT_Export/RF2Release/Terminology/${sourceFileRenamed}"
		echo "Copying ${sourceFile} to ${newLocation}"
		cp ${sourceFile} ${newLocation}
	else
		echo "Appending ${sourceFile} to ${targetFile}"
		tail -n +2 ${sourceFile} >> ${targetFile}
	fi
}


downloadpreviousPackage() {
	if [ -f "${previousPackage}" ]; then
		echo "Previous published release ${previousPackage} already present"
	else
		echo "Downloading previous published release: ${previousPackage} from S3 ${s3BucketLocation}"
		aws s3 cp --no-progress s3://${s3BucketLocation}${previousPackage} ./
	fi
}

uploadSourceFiles() {
	today=`date +'%Y%m%d'`
	echo "Renaming rf2 files to target effective date: $effectiveDate"
	for file in `find . -type f -path "./${converted_file_location}/*" -name '*.txt'`;
	do
		mv -- "$file" "${file//${today}/${effectiveDate}}" || true
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

callSrs() {
	echo "Deleting previous delta source files from: $source "
	curl ${commonParams} -X DELETE ${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/sourcefiles/${source} | grep HTTP | ensureCorrectResponse

	echo "Deleting previous delta Input Files "
	curl ${commonParams} -X DELETE ${release_url}/api/v1/centers/${releaseCenter}/products/${productKey}/inputfiles/*.txt | grep HTTP | ensureCorrectResponse

	uploadSourceFiles

	echo "Preparing configuration for product: $product_key"
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
downloadpreviousPackage
mkdir -p ${converted_file_location}
rm -r ./${converted_file_location}/* || true
echo "Performing Concrete Domain Conversion..."
java -jar target/CdConversion.jar -s ${previousPackage} -d ${deltaArchiveFile}
classify
applyClassificationChanges
callSrs
