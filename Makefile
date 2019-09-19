.EXPORT_ALL_VARIABLES:
.PHONY: test

PG3_IMAGE=healthsamurai/db3:latest

repl:
	source .env && clj -A:nrepl -e "(-main)" -r 

jar:
	clj -A:build

dock:
	docker build -t ${PG3_IMAGE} .

pub:
	docker push ${PG3_IMAGE}

deploy:
	kubectl apply -f deploy.yaml

all: jar dock pub deploy

test:
	clj -A:test


