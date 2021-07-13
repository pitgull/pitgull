package io.pg.nix

import Nix.syntax._
import weaver._

object NixRenderTest extends SimpleIOSuite {
  pureTest("single line string") {
    expect(Nix.Str("foo").render == "\"foo\"")
  }

  pureTest("multiline string") {
    expect(Nix.Str("foo\nbar").render == "''foo\nbar''")
  }

  pureTest("empty record") {
    expect(Nix.Record(Map.empty).render == "{ }")
  }

  pureTest("single-element record") {
    expect(Nix.Record(Map(RecordEntry("key") -> "value".toNix)).render == """{ key = "value"; }""")
  }

  pureTest("multi-elem record") {
    val input = Nix.Record(Map(RecordEntry("key") -> "value".toNix, RecordEntry("key2") -> "value2".toNix))

    expect(input.render == """{ key = "value"; key2 = "value2"; }""")
  }

  pureTest("nested record") {
    val input =
      Nix.Record(Map(RecordEntry("obj") -> Nix.Record(Map(RecordEntry("nested") -> "value2".toNix))))

    expect(input.render == """{ obj = { nested = "value2"; }; }""")
  }

  pureTest("select on symbol") {
    expect(Nix.Name("builtins").select("fetchurl").render == "builtins.fetchurl")
  }

  pureTest("select on object") {
    expect(Nix.obj("k" := Nix.Name("v")).select("k").render == "{ k = v; }.k")
  }

  pureTest("select on import") {
    expect(Nix.Name("path").imported.select("k").render == "(import path).k")
  }

  pureTest("import on select") {
    expect(Nix.Name("path").select("k").imported.render == "import path.k")
  }

  pureTest("select on apply") {
    expect(Nix.Name("fun").applied(Nix.Name("arg")).select("v").render == "(fun arg).v")
  }

  pureTest("apply") {
    expect(Nix.Name("function").applied(Nix.Name("myurl")).render == "function myurl")
  }

  pureTest("left associated apply") {
    expect(Nix.Name("f").applied(Nix.Name("arg1")).applied(Nix.Name("arg2")).render == "f arg1 arg2")
  }

  pureTest("right associated apply") {
    expect(Nix.Name("fun1").applied(Nix.Name("fun2").applied(Nix.Name("arg"))).render == "fun1 (fun2 arg)")
  }

  pureTest("apply on select") {
    expect(Nix.Name("selectee").select("selection").applied(Nix.Name("arg")).render == "selectee.selection arg")
  }
  pureTest("apply with select") {
    expect(Nix.Name("fun").applied(Nix.Name("selectee").select("selection")).render == "fun selectee.selection")
  }

  pureTest("symbol application with object") {
    expect(Nix.Name("function").applied(Nix.obj("k" := Nix.Name("v"))).render == "function { k = v; }")
  }

  pureTest("left associated function import") {
    expect(Nix.Name("importee").imported.applied(Nix.Name("arg")).render == "(import importee) arg")
  }

  pureTest("right associated function import") {
    expect(Nix.Name("function").applied(Nix.Name("arg")).imported.render == "import (function arg)")
  }

  pureTest("nested import") {
    expect(Nix.Name("path").imported.imported.render == "import (import path)")
  }

}
