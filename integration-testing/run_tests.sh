#!/bin/bash -e

if [[ ! -f Dockerfile.tmp || Dockerfile.in -nt Dockerfile.tmp ]]; then
	if [[ -n $DRONE_BUILD_NUMBER ]]; then
		tag=DRONE-$DRONE_BUILD_NUMBER
	else
		tag=latest
	fi
	sed "s/@@TAG@@/$tag/" Dockerfile.in >Dockerfile.tmp
fi

docker build --tag rchain-integration-testing -f Dockerfile.tmp .
pipenv run py.test -v "$@"
