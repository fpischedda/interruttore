# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).


## [0.1.3]
### Fixed
- Fix issue #18: bug when dealing with multiple exception types


## [0.1.2]
### Added
- Library should work with both Clojure and ClojureScript.
- Started using deps.end, especially for cljs tests...
- but still using lein for deployments to clojars.
### Fixed
- Use Reader conditionals for platform specific code, should be
  complete now.


## [0.1.1]
### Added
- _CHANGELOG.md_ created.
- Add basic support to ClojureScript.
### Fixed
- Fix retry-after calculation based on retry-after-ms option.


[Unreleased]: https://github.com/fpischedda/interruttore/compare/0.1.1...HEAD

[0.1.2]: https://github.com/fpischedda/interruttore/releases/tag/0.1.2

[0.1.3]: https://github.com/fpischedda/interruttore/releases/tag/0.1.3
