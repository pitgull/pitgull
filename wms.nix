let
  pg = import (
    # builtins.fetchurl {
    #   url = "https://raw.githubusercontent.com/polyvariant/pitgull/60eff26a70d348d591b4fd2f1546846ee2cfcc76/pitgull.nix";
    #   sha256 = "0rh588p2xwaa8brw41r8s8p2599p58abc6dd97vrgsddixbfvpqh";
    # }
    ./nix/pitgull.nix
  );
  semver = level: pg.description.matches ".*labels:.*semver-${level}.*";
  patchUpdate = (semver "patch");
  wmsLibraryMinorUpdate = pg.allOf [
    (semver "minor")
    (pg.description.matches ".*((com\.ocado\.ospnow\.wms)|(com\.ocado\.gm\.wms)).*")
  ];
in
pg.allOf [
  (pg.anyOf [ (pg.author.equals "scala_steward") (pg.author.equals "kubukoz") ])
  (pg.status.equals pg.status.success)
  (
    pg.anyOf [
      patchUpdate
      wmsLibraryMinorUpdate
    ]
  )
]
