load("@rules_java//java:defs.bzl", "java_library")
load("//tools/bzl:javadoc.bzl", "java_doc")

CONSTANTS_SRC = [
    "documentation/Constants.java",
]

GERRIT_GLOBAL_MODULE_SRC = [
    "config/GerritGlobalModule.java",
]

TESTING_SRC = [
    "account/externalids/testing/ExternalIdInserter.java",
    "account/externalids/testing/ExternalIdTestUtil.java",
]

PROLOG_SRC = ["rules/prolog/*.java"]

java_library(
    name = "constants",
    srcs = CONSTANTS_SRC,
    visibility = ["//visibility:public"],
)

# Giant kitchen-sink target.
#
# The only reason this hasn't been split up further is because we have too many
# tangled dependencies (and Guice unfortunately makes it quite easy to get into
# this state). Which means if you see an opportunity to split something off, you
# should seize it.
java_library(
    name = "server",
    srcs = glob(
        ["**/*.java"],
        exclude = CONSTANTS_SRC + GERRIT_GLOBAL_MODULE_SRC + TESTING_SRC +
                  PROLOG_SRC,
    ),
    resource_strip_prefix = "resources",
    resources = ["//resources/com/google/gerrit/server"],
    visibility = ["//visibility:public"],
    deps = [
        ":constants",
        "//java/com/google/gerrit/common:annotations",
        "//java/com/google/gerrit/common:server",
        "//java/com/google/gerrit/entities",
        "//java/com/google/gerrit/exceptions",
        "//java/com/google/gerrit/extensions:api",
        "//java/com/google/gerrit/git",
        "//java/com/google/gerrit/index",
        "//java/com/google/gerrit/index:query_exception",
        "//java/com/google/gerrit/index/project",
        "//java/com/google/gerrit/jgit",
        "//java/com/google/gerrit/json",
        "//java/com/google/gerrit/lifecycle",
        "//java/com/google/gerrit/mail",
        "//java/com/google/gerrit/metrics",
        "//java/com/google/gerrit/prettify:server",
        "//java/com/google/gerrit/proto",
        "//java/com/google/gerrit/server/cache/serialize",
        "//java/com/google/gerrit/server/cache/serialize/entities",
        "//java/com/google/gerrit/server/cancellation",
        "//java/com/google/gerrit/server/data",
        "//java/com/google/gerrit/server/git/receive:ref_cache",
        "//java/com/google/gerrit/server/ioutil",
        "//java/com/google/gerrit/server/logging",
        "//java/com/google/gerrit/server/util/git",
        "//java/com/google/gerrit/server/util/time",
        "//java/com/google/gerrit/util/cli",
        "//java/com/google/gerrit/util/concurrent",
        "//java/org/apache/commons/net",
        "//lib:args4j",
        "//lib:autolink",
        "//lib:automaton",
        "//lib:blame-cache",
        "//lib:flexmark-all-lib",
        "//lib:gson",
        "//lib:guava",
        "//lib:guava-retrying",
        "//lib:icu4j",
        "//lib:jgit",
        "//lib:jgit-archive",
        "//lib:mime-util",
        "//lib:protobuf",
        "//lib:roaringbitmap",
        "//lib:servlet-api",
        "//lib:soy",
        "//lib:tukaani-xz",
        "//lib/auto:auto-factory",
        "//lib/auto:auto-value",
        "//lib/auto:auto-value-annotations",
        "//lib/bouncycastle:bcpkix-neverlink",
        "//lib/bouncycastle:bcprov-neverlink",
        "//lib/commons:codec",
        "//lib/commons:compress",
        "//lib/commons:dbcp",
        "//lib/commons:lang3",
        "//lib/commons:net",
        "//lib/commons:text",
        "//lib/commons:validator",
        "//lib/errorprone:annotations",
        "//lib/flogger:api",
        "//lib/guice",
        "//lib/guice:guice-assistedinject",
        "//lib/guice:guice-servlet",
        "//lib/httpcomponents:httpclient",
        "//lib/jsoup",
        "//lib/log:log4j",
        "//lib/lucene:lucene-analyzers-common",
        "//lib/lucene:lucene-core",
        "//lib/lucene:lucene-queryparser",
        "//lib/mime4j:core",
        "//lib/mime4j:dom",
        "//lib/ow2:ow2-asm",
        "//lib/ow2:ow2-asm-tree",
        "//lib/ow2:ow2-asm-util",
        "//lib/prolog:runtime",
        "//proto:cache_java_proto",
        "//proto:entities_java_proto",
    ],
)

# Large modules that import things from all across the server package
# hierarchy, so they need lots of dependencies.
java_library(
    name = "module",
    srcs = GERRIT_GLOBAL_MODULE_SRC,
    visibility = ["//visibility:public"],
    deps = [
        ":server",
        "//java/com/google/gerrit/entities",
        "//java/com/google/gerrit/extensions:api",
        "//java/com/google/gerrit/server/git/receive",
        "//java/com/google/gerrit/server/logging",
        "//java/com/google/gerrit/server/restapi",
        "//java/com/google/gerrit/server/rules/prolog",
        "//java/com/google/gerrit/server/version",
        "//lib:blame-cache",
        "//lib:guava",
        "//lib:jgit",
        "//lib:soy",
        "//lib/guice",
    ],
)

java_doc(
    name = "doc",
    libs = [":server"],
    pkgs = ["com.google.gerrit"],
    title = "Gerrit Review Server Documentation",
)
