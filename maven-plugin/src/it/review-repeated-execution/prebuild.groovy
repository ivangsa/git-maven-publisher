import io.github.ivangsa.gitpublisher.maven.it.ItSupport
ItSupport.prebuild(basedir)
try (def bare = ItSupport.bare(basedir)) {
    for (def ref : bare.repository().getRefDatabase().getRefsByPrefix('refs/')) {
        def update = bare.repository().updateRef(ref.getName())
        update.setForceUpdate(true)
        update.delete()
    }
}
return true
