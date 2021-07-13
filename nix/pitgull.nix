let
  # used only  for debugging - we don't need nixpkgs otherwise
  lib = (import <nixpkgs> {}).lib;
  inherit (builtins) map;

  mkMismatch = kind: { expected, actual }: { inherit kind expected actual; };

  mismatches = {
    status = mkMismatch "status";
    author = mkMismatch "author";
    description = mkMismatch "description";
    text = {
      equal = { expected }: { kind = "equal";inherit expected; };
      matches = { pattern }: { kind = "matches"; inherit pattern; };
    };
    noneMatched = mismatches: { kind = "none_matched"; inherit mismatches; };
  };
  mkTextMatchers = { path, makeMismatch }: {
    equals = expected: input:
      let
        actual = path input;
      in
        ensureOr (actual == expected) (
          makeMismatch {
            expected = mismatches.text.equal { inherit expected; };
            inherit actual;
          }
        );
    matches = pattern: input:
      let
        actual = path input;
      in
        ensureOr (builtins.match pattern actual != null) (
          makeMismatch {
            expected = mismatches.text.matches { inherit pattern; };
            inherit actual;
          }
        );
  };
  results = rec {
    ok = {
      kind = "ok";
    };
    notOk = mismatch: notOkMany [ mismatch ];
    notOkMany = mismatches: {
      kind = "not_ok";
      inherit mismatches;
    };
  };
  ensureOr = bool: msg: if bool then results.ok else results.notOk msg;
  allResults = matchers: input:
    let
      results = (map (a: a input) matchers);
      failed = builtins.filter ({ kind, ... }: kind == "not_ok") results;
      passed = builtins.filter ({ kind, ... }: kind == "ok") results;
    in
      {
        failed = builtins.concatMap ({ mismatches, ... }: mismatches) failed;
        passedCount = builtins.length passed;
      };
in
{
  status = {
    success = "success";
    equals = expected: { status, ... }: ensureOr (status == expected) (mismatches.status { inherit expected; actual = status; });
  };
  author = mkTextMatchers {
    path = { author, ... }: author;
    makeMismatch = mismatches.author;
  };
  description = mkTextMatchers {
    path = { description, ... }: description;
    makeMismatch = mismatches.description;
  };
  allOf = matchers: input:
    let
      out = allResults matchers input;
    in
      if builtins.length out.failed == 0 then results.ok else results.notOkMany out.failed;

  anyOf = matchers: input:
    let
      out = allResults matchers input;
    in
      ensureOr (out.passedCount > 0) (mismatches.noneMatched out.failed);

  internal = { inherit results mismatches; };
}
