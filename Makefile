dev:
	npx shadow-cljs node-repl

test-watch:
	bin/kaocha --watch

test:
	bin/kaocha
