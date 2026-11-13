UPDATE application_settings
SET organization_json = json_set(
        organization_json,
        '$.categories.MOVIE.folderTemplate',
        CASE json_extract(organization_json, '$.categories.MOVIE.folderTemplate')
            WHEN '{title}[ ({year})]' THEN '{title}[ ({year})][ {providerId}]'
            ELSE json_extract(organization_json, '$.categories.MOVIE.folderTemplate') END,
        '$.categories.SERIES.folderTemplate',
        CASE json_extract(organization_json, '$.categories.SERIES.folderTemplate')
            WHEN '{title}[ ({year})]/Season {season:02}' THEN '{title}[ ({year})][ {providerId}]/Season {season:02}'
            ELSE json_extract(organization_json, '$.categories.SERIES.folderTemplate') END,
        '$.categories.ANIME.folderTemplate',
        CASE json_extract(organization_json, '$.categories.ANIME.folderTemplate')
            WHEN '{title}[ ({year})]/Season {season:02}' THEN '{title}[ ({year})][ {providerId}]/Season {season:02}'
            ELSE json_extract(organization_json, '$.categories.ANIME.folderTemplate') END,
        '$.revision', organization_revision + 1
    ),
    organization_revision = organization_revision + 1,
    configuration_revision = configuration_revision + 1
WHERE json_extract(organization_json, '$.categories.MOVIE.folderTemplate') = '{title}[ ({year})]'
   OR json_extract(organization_json, '$.categories.SERIES.folderTemplate') = '{title}[ ({year})]/Season {season:02}'
   OR json_extract(organization_json, '$.categories.ANIME.folderTemplate') = '{title}[ ({year})]/Season {season:02}';
