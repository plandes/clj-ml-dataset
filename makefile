## makefile automates the build and deployment for lein projects

# type of project, currently one of: clojure, python
PROJ_TYPE=		clojure

# make build dependencies
_ :=	$(shell [ ! -d .git ] && git init ; [ ! -d zenbuild ] && \
	  git submodule add https://github.com/plandes/zenbuild && make gitinit )

include ./zenbuild/main.mk

.PHONY: test
test:
	$(LEIN) test
