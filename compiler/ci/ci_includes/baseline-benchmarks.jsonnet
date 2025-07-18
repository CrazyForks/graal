{
  local c = (import '../../../ci/ci_common/common.jsonnet'),
  local utils = (import '../../../ci/ci_common/common-utils.libsonnet'),
  local bc = (import '../../../ci/ci_common/bench-common.libsonnet'),
  local cc = (import '../ci_common/compiler-common.libsonnet'),
  local bench = (import '../ci_common/benchmark-suites.libsonnet'),
  local hw = bc.bench_hw,

  local hotspot_builds = std.flattenArrays([
    [
      c.weekly + hw.x52 + jdk + cc.c2 + suite,
      c.weekly + hw.a12c + jdk + cc.c2 + suite
    ]
  for jdk in cc.product_jdks
  for suite in bench.groups.all_suites
  ]),

  local hotspot_profiling_builds = std.flattenArrays([
    [
    c.monthly + hw.x52  + jdk + cc.c2 + suite + cc.enable_profiling    + { job_prefix:: "bench-compiler-profiling" },
    c.monthly + hw.a12c + jdk + cc.c2 + suite + cc.enable_profiling    + { job_prefix:: "bench-compiler-profiling" },
    c.monthly + hw.x52  + jdk + cc.c2 + suite + cc.footprint_tracking  + { job_prefix:: "bench-compiler-footprint" },
    c.monthly + hw.a12c + jdk + cc.c2 + suite + cc.footprint_tracking  + { job_prefix:: "bench-compiler-footprint" },
    c.monthly + hw.x52_root + jdk + cc.c2 + suite + cc.energy_tracking + { job_prefix:: "bench-compiler-energy" }
    ]
  for jdk in cc.product_jdks
  for suite in bench.groups.main_suites
  ]),

  local weekly_forks_amd64_builds = std.flattenArrays(std.flattenArrays([
    [
    bc.generate_fork_builds(c.weekly + hw.x52 + jdk + cc.c2 + suite),
    bc.generate_fork_builds(c.weekly + hw.e3  + jdk + cc.c2 + suite)
    ]
  for jdk in cc.product_jdks
  for suite in bench.groups.weekly_forks_suites
  ])),

  local weekly_forks_aarch64_builds = std.flattenArrays([
    bc.generate_fork_builds(c.weekly + hw.a12c + jdk + cc.c2 + suite)
  for jdk in cc.product_jdks
  for suite in bench.groups.weekly_forks_suites
  ]),

  local economy_builds = [
      c.weekly + hw.x52 + jdk + cc.libgraal + cc.economy_mode + suite
    for jdk in cc.product_jdks
    for suite in bench.groups.main_suites
  ],
  local no_tiered_builds = std.flattenArrays([
    [
    c.monthly + hw.x52 + jdk + cc.c1                                             + suite,
    c.monthly + hw.x52 + jdk + cc.c2                         + cc.no_tiered_comp + suite,
    c.monthly + hw.x52 + jdk + cc.libgraal + cc.economy_mode + cc.no_tiered_comp + suite
    ]
  for jdk in cc.product_jdks
  for suite in bench.groups.main_suites
  ]),

  local gc_variants_builds = std.flattenArrays([
    [
    c.monthly + hw.x52 + jdk + cc.c2                         + cc.zgc_mode + suite,
    ]
  for jdk in cc.product_jdks
  for suite in bench.groups.main_suites
  ]) + std.flattenArrays([
    [
    c.monthly + hw.x52 + jdk + cc.c2                         + cc.serialgc_mode + bench.microservice_benchmarks,
    c.monthly + hw.x52 + jdk + cc.c2                         + cc.pargc_mode    + bench.microservice_benchmarks,
    c.monthly + hw.x52 + jdk + cc.c2                         + cc.zgc_mode      + bench.microservice_benchmarks,
    ]
  for jdk in cc.product_jdks
  ]),
  local all_builds = hotspot_builds + hotspot_profiling_builds +
    weekly_forks_amd64_builds + weekly_forks_aarch64_builds + economy_builds + no_tiered_builds + gc_variants_builds,
  local filtered_builds = [b for b in all_builds if b.is_jdk_supported(b.jdk_version) && b.is_arch_supported(b.arch)],

  // adds a "defined_in" field to all builds mentioning the location of this current file
  builds:: utils.add_defined_in(filtered_builds, std.thisFile),
}
