# SPDX-License-Identifier: LGPL-2.1-or-later
.PHONY: all build test clean docs docs-clj docs-cpp docs-guide

BUILD_DIR ?= build
CMAKE_FLAGS ?= -DCMAKE_BUILD_TYPE=Release

# ---------------------------------------------------------------------------
# Default target
# ---------------------------------------------------------------------------

all: build

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

build: build-clj build-cpp

build-clj:
	lein compile

build-cpp: $(BUILD_DIR)/Makefile
	cmake --build $(BUILD_DIR) --parallel

$(BUILD_DIR)/Makefile:
	cmake -S . -B $(BUILD_DIR) $(CMAKE_FLAGS)

build-link: $(BUILD_DIR)/Makefile
	cmake -S . -B $(BUILD_DIR) $(CMAKE_FLAGS) -DCLJSEQ_ENABLE_LINK=ON
	cmake --build $(BUILD_DIR) --parallel

# ---------------------------------------------------------------------------
# Test
# ---------------------------------------------------------------------------

test: test-clj

test-clj:
	lein test

test-cpp: $(BUILD_DIR)/Makefile
	cmake --build $(BUILD_DIR) --target cljseq-tests --parallel
	ctest --test-dir $(BUILD_DIR) --output-on-failure

test-python:
	python -m pytest python/tests

# ---------------------------------------------------------------------------
# Documentation
# ---------------------------------------------------------------------------

docs: docs-clj docs-cpp docs-guide

docs-clj:
	lein codox

docs-cpp: $(BUILD_DIR)/Makefile
	doxygen cpp/Doxyfile

docs-guide:
	mdbook build doc/book

# ---------------------------------------------------------------------------
# Clean
# ---------------------------------------------------------------------------

clean: clean-clj clean-cpp

clean-clj:
	lein clean

clean-cpp:
	rm -rf $(BUILD_DIR)

clean-all: clean
	rm -rf target/codox
