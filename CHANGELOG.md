# Change Log
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).


## [Unreleased]


## [0.0.12] - 2018-06-24
### Added
- File system based data source `zensols.dataset.thaw` namespace.
- Clojure 1.9 profile.
- Unit test case.
- Contributions file.
- Ability to filter by key in 

### Changed
- Moved to MIT license.
- Move to zenbuild submodule based build.
- Fix cross train/test stratification by class shuffle.


## [0.0.11] - 2017-10-17
### Changed
- Fix bug where exporting the database results in characters spanning multiple
  columns in the output (csv) file.

## [0.0.10] - 2017-06-10
### Changed
- Logging bug fixes.
- Guard against nil IDs.


## [0.0.9] - 2017-05-16
### Added
- Cache instances feature.

### Changed
- No longer require integer IDs during loading.


## [0.0.8] - 2017-04-27
### Added
- Changelog
- Configure ES mappings.
- Stratification by class label.

### Changed
- Moved to lein-git-version 1.2.7
- Fixed double properties level mapping in ES.
- More robust dataset spreadsheet creation.


## [0.0.7] - 2016-12-13
### Added
- Much more logging.

### Changed
- Move to zenbuild.
- Dependency versions


[Unreleased]: https://github.com/plandes/clj-ml-dataset/compare/v0.0.12...HEAD
[0.0.12]: https://github.com/plandes/clj-ml-dataset/compare/v0.0.11...v0.0.12
[0.0.11]: https://github.com/plandes/clj-ml-dataset/compare/v0.0.10...v0.0.11
[0.0.10]: https://github.com/plandes/clj-ml-dataset/compare/v0.0.9...v0.0.10
[0.0.9]: https://github.com/plandes/clj-ml-dataset/compare/v0.0.8...v0.0.9
[0.0.8]: https://github.com/plandes/clj-ml-dataset/compare/v0.0.7...v0.0.8
[0.0.7]: https://github.com/plandes/clj-ml-dataset/compare/v0.0.6...v0.0.7
[0.0.6]: https://github.com/plandes/clj-ml-dataset/compare/v0.0.5...v0.0.6
