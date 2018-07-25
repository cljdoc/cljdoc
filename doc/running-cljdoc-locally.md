# Running cljdoc locally

Besides development purposes running cljdoc locally can be useful to
build documentation for projects without pushing any changes to
Clojars and your Git remote.

Here's a step by step instruction to run cljdoc locally and build docs
for `muuntaja` from a local Jar and Git repository:

1. Clone cljdoc

   ```sh
   git clone https://github.com/cljdoc/cljdoc.git
   cd cljdoc/
   ```

1. Start the cljdoc server

    ```sh
    ./script/cljdoc run
    ```

    > This will start the cljdoc server process, requiring you to open another shell for the following steps.

1. Clone `muuntaja` to a direcory of your liking and install it into your local Maven repo:

    ```sh
    git clone https://github.com/metosin/muuntaja.git
    cd muuntaja/
    lein install
    ```

1. Ingest all relevant information into the cljdoc system:

    ```sh
    cd cljdoc/
    ./script/cljdoc ingest -p metosin/muuntaja \
                           -v 0.6.0-alpha1 \
                           --jar ~/.m2/repository/metosin/muuntaja/0.6.0-alpha1/muuntaja-0.6.0-alpha1.jar \
                           --pom ~/.m2/repository/metosin/muuntaja/0.6.0-alpha1/muuntaja-0.6.0-alpha1.pom \
                           --git /path/to/muuntaja/repo \
                           --rev "master"
    ```

    > **Tip:** See `./script/cljdoc ingest --help` for details.

    > **Note:** For Git-based changes to take effect you need to
    > commit them so there is a revision that the repo can be analsed
    > at. This can be done in a branch of course.

1. Open the docs for muuntaja on the local cljdoc server: http://localhost:8000/d/metosin/muuntaja

---

**Thats pretty much it!** Stop by on Slack if you have any problems. :wave:
