# Running cljdoc locally

## Introduction
If you are a developer wanting to contribute to cljdoc itself, you'll want to
[run cljdoc locally from source](#run-cljdoc-locally-from-source).

If you are a library author only interested in verifying that your your docs
look good before pushing, you can also run locally from source, but you might opt
to instead [run cljdoc locally from docker](#run-cljdoc-locally-from-docker).

## Run cljdoc Locally from Source

### Setup cljdoc

1. Clone cljdoc

   ```sh
   git clone https://github.com/cljdoc/cljdoc.git
   cd cljdoc/
   ```

1. Build the Javascript files used by the cljdoc pages:

    ```sh
    npm ci                 # reproducibly install packages into node_modules
    npm run format         # format JS code with Prettier
    npm run build          # production build
    ```

    Check that `resources-compiled` folder was generated.

    If you are a developer planning to work on JS files, instead of `npm run build` run `dev`:

    ```sh
    npm ci                 # reproducibly install packages into node_modules
    npm run format         # format JS code with Prettier
    npm run dev            # watch mode
    ```

1. Start the cljdoc server

    ```sh
    ./script/cljdoc run
    ```

    > **Note**: This will start the cljdoc server process, requiring you to open another shell for the following steps.

    Alternatively you can start a REPL with `clj` (or your favorite editor integration),
    then load the system, move into its namespace and start the server:

    ```clj
    (require '[cljdoc.server.system])
    (in-ns 'cljdoc.server.system)
    (require '[integrant.repl])
    (integrant.repl/set-prep! #(system-config (cfg/config)))
    (integrant.repl/go)
    ```

1. (Optional) Run tests using [Kaocha](https://github.com/lambdaisland/kaocha):

    ```
    clj -A:test
    ```

### Import a Project from Local Sources

Here we use the popular `muuntaja` project to illustrate how to build docs from a local
jar and git repository. It's not a bad idea to actually go through these steps as a sanity
test before mapping this knowledge onto your own project.

1. Clone `muuntaja` to a directory of your liking and install it into your local Maven repo:

    ```sh
    git clone --branch 0.6.1 https://github.com/metosin/muuntaja.git
    cd muuntaja/
    scripts/lein-modules install
    ```

    > **Tip:** You can checkout a different specific release version instead of 0.6.1.
    > When you do so, remember to change the version number in the command in the
    > next step.

    > **Tip:** installing to your local maven repo will vary by build tech
    > (leiningen, boot, tools deps). The `scripts/lein-modules install` command is
    > appropriate for `muuntaja`, be sure to use the appropriate command for your
    > project.

1. Analyze and ingest your project into the cljdoc system:

    ```sh
    cd cljdoc/
    ./script/cljdoc ingest -p metosin/muuntaja -v 0.6.1 --git /path/to/muuntaja/repo
    ```

    Or, if you want to specify the jar, pom, git repo and revision explicitly:

    ```sh
    cd cljdoc/
    ./script/cljdoc ingest -p metosin/muuntaja \
                           -v 0.6.1 \
                           --jar ~/.m2/repository/metosin/muuntaja/0.6.1/muuntaja-0.6.1.jar \
                           --pom ~/.m2/repository/metosin/muuntaja/0.6.1/muuntaja-0.6.1.pom \
                           --git /path/to/muuntaja/repo \
                           --rev "master"
    ```

    > **Tip:** Run `./script/cljdoc ingest --help` for details on all ingest
    > command line options.

    > **Note:** For Git-based changes to take effect, you need to
    > commit them locally so there is a revision that the repo can be analysed
    > at. This can be done in a branch, if you don't want to pollute your master.

1. Open the docs for muuntaja on the local cljdoc server: http://localhost:8000/d/metosin/muuntaja

## Run cljdoc Locally from docker

The [cljdoc docker image](https://hub.docker.com/r/cljdoc/cljdoc/tags) can be
used to run cljdoc locally without building it yourself. We'll use the
`muuntaja` project to illustrate how this works.

1. Follow step 1 from [import a project from local sources](#import-a-project-from-local-sources).

1. Make a directory in which the cljdoc sqlite database will be persisted, e.g. `/tmp/cljdoc`

1. Ingest the library into the cljdoc sqlite database:

     ```sh
     docker run --rm -v /path/to/muuntaja/repo:/muuntaja \
       -v "$HOME/.m2:/root/.m2" -v /tmp/cljdoc:/app/data --entrypoint "clojure" \
       cljdoc/cljdoc -A:cli ingest -p metosin/muuntaja -v 0.6.1 \
       --git /muuntaja
     ```
1. Run the web server:

     `docker run --rm -p 8000:8000 -v /tmp/cljdoc:/app/data cljdoc/cljdoc`

1. Open the docs for `muuntaja` on the local cljdoc server running on docker:
   http://localhost:8000/d/metosin/muuntaja/0.6.1/doc/readme

Example scripts to preview docs locally using docker before publishing:

- [clj-kondo](https://github.com/borkdude/clj-kondo/blob/master/script/cljdoc-preview)

---

**Thats pretty much it!** Stop by on Slack if you have any problems. :wave:
