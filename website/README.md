# Versioned GitHub Pages documentation

The documentation site publishes each mod release separately. The first release is **0.1.1**. Search is scoped to the selected mod version, and the version selector keeps the current page when it exists in another release.

The website root is **http://sc.jvrcruz.games/**. A version's root is **http://sc.jvrcruz.games/X.X.X/**, for example [0.1.2](http://sc.jvrcruz.games/0.1.2/) or [0.1.1](http://sc.jvrcruz.games/0.1.1/). Chapter paths are relative to that version, without a `/SiliconCraft/` prefix.

`docs/` is the working manual. `website/versions/<version>/docs/` contains a preserved release snapshot, including downloadable examples, the project README, MIT license, and logo. Each snapshot also stores its chapter navigation. `website/versions.json` lists available releases, their content checksums, and the latest version. Updating the website theme does not change archived documentation sources.

The builder also updates legacy GitHub Pages documentation URLs in its temporary rendering copies to the configured website root. Archived source files and their checksums remain unchanged.

## Local build

```sh
python3 -m venv .venv-docs
.venv-docs/bin/python -m pip install -r website/requirements.txt
.venv-docs/bin/python scripts/docs_site.py check
.venv-docs/bin/python -m unittest discover -s scripts -p 'test_docs_site.py'
.venv-docs/bin/python scripts/docs_site.py build
.venv-docs/bin/python -m http.server 8000 --directory build/docs-site
```

Open `http://localhost:8000/`. To use the version selector locally, build with `--base-url http://localhost:8000/`. For a different GitHub Pages project path or custom domain, pass its complete URL using `--base-url`. Generated HTML stays under the ignored `build/` directory.

## Every mod version bump

1. Update `mod_version` in `gradle.properties`.
2. Finish the documentation and examples in `docs/` for that release. Keep its four chapter folders.
3. Run `python3 scripts/docs_site.py snapshot <new-mod-version>`.
4. Commit the new `website/versions/<new-mod-version>/` folder and updated `website/versions.json` with the version bump. Do not replace older snapshots.
5. Run the snapshot checks, website build, `./gradlew test`, and `./gradlew build`.

The snapshot command requires an exact match with `mod_version` and refuses to overwrite an existing version. CI fails if a version bump is missing its documentation entry or an archived snapshot no longer matches its checksum. Ordinary changes in `docs/` remain working documentation until the next snapshot. Snapshot corrections should be reviewed explicitly rather than regenerated during every deployment.

## GitHub Pages deployment

The workflow in [`.github/workflows/docs.yml`](../.github/workflows/docs.yml) builds all snapshots for pull requests. Pushes to `master` and manual workflow runs also deploy the generated artifact using GitHub's Pages actions. It never pushes generated files to a `gh-pages` branch.

In the repository's **Settings → Pages → Build and deployment**, choose **GitHub Actions** as the source. Then push the workflow to `master` or run **Versioned documentation** manually. GitHub's `github-pages` environment and repository Actions settings must permit the deployment.

The workflow uses the default `http://sc.jvrcruz.games/` base URL. The build copies `website/CNAME` to the site root. In **Settings → Pages → Custom domain**, configure `sc.jvrcruz.games`, and point that subdomain's DNS CNAME to `jvrcruzGAMES.github.io` (without a repository path). GitHub Pages deployments through Actions still require the custom domain to be configured in repository settings; an artifact's CNAME file alone does not configure it.

The site uses [MkDocs](https://www.mkdocs.org/) and [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/). The workflow follows [GitHub's custom Pages deployment instructions](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).
