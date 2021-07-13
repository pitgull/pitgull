let
  pkgs = import <nixpkgs> {};
  inherit (pkgs) lib;
  pg = import ./pitgull.nix;
  pgi = pg.internal;

  ok = pgi.results.ok;
  notOk = pgi.results.notOk;

  simpleFail = msg: pgi.mismatches.status { expected = "impossible ${msg}"; actual = "also not possible ${msg}"; };
  alwaysFail = msg: _: notOk (simpleFail msg);
  alwaysSucceed = _: ok;
  undefined = throw "undefined - this shouldn't be evaluated ever! Tests are probably broken if you see this while running them.";
in

let
  allOfTests = {
    testAllOfEmptyIsOk = {
      expr = pg.allOf [] undefined;
      expected = ok;
    };

    testAllOfOk = {
      expr = pg.allOf [ alwaysSucceed alwaysSucceed alwaysSucceed ] undefined;
      expected = ok;
    };

    testAllOfOneBrokenNotOk = {
      expr = pg.allOf [ alwaysSucceed (alwaysFail "1") alwaysSucceed ] undefined;
      expected = alwaysFail "1" undefined;
    };


    testAllOfSomeBrokenNotOk = {
      expr = pg.allOf [
        alwaysSucceed
        (alwaysFail "1")
        alwaysSucceed
        (alwaysFail "2")
        alwaysSucceed
        (alwaysFail "3")
      ] undefined;
      expected = pgi.results.notOkMany (builtins.map simpleFail [ "1" "2" "3" ]);
    };
  };

  anyOfTests = {
    testAnyOfEmptyIsFailed = {
      expr = pg.anyOf [] undefined;
      expected = notOk (pgi.mismatches.noneMatched []);
    };

    testAnyOfSingleSuccessIsOk = {
      expr = pg.anyOf [ alwaysSucceed ] undefined;
      expected = ok;
    };

    testAnyOfSomeSuccessesIsOk = {
      expr = pg.anyOf [ (alwaysFail "1") alwaysSucceed (alwaysFail "2") ] undefined;
      expected = ok;
    };

    testAnyOfAllFailuresIsFailed = {
      expr = pg.anyOf [ (alwaysFail "1") (alwaysFail "2") (alwaysFail "3") ] undefined;
      expected = notOk (
        pgi.mismatches.noneMatched (builtins.map simpleFail [ "1" "2" "3" ])
      );
    };
  };

  statusTests = {
    testStatusMatch = {
      expr = pg.status.equals "good" { status = "good"; };
      expected = ok;
    };
    testStatusMismatch = {
      expr = pg.status.equals "good" { status = "bad"; };
      expected = notOk (pgi.mismatches.status { expected = "good"; actual = "bad"; });
    };
  };

  regexTests = {
    testRegexDotMatchesNewline = {
      expr = pg.description.matches "hello.+world" {
        description = ''hello
                        great
                        world'';
      };
      expected = ok;
    };
    testRegexNegative = {
      expr = pg.description.matches "hello.+world" {
        description = ''goodbye world'';
      };
      expected = notOk (
        pgi.mismatches.description {
          expected = pgi.mismatches.text.matches { pattern = "hello.+world"; };
          actual = "goodbye world";
        }
      );
    };
  };

in

  lib.runTests (
    anyOfTests // allOfTests // statusTests // regexTests
  )
