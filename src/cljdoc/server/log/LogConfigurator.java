package cljdoc.server.log;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.spi.ContextAwareBase;

/**
 * Invoked by as service via resources/META-INF/services/ch.qos.logback.classic.spi.Configurator
 *
 * Delegates to `cljdoc.server.log.init/configure` so that we can carry on in Clojure.
 */
public class LogConfigurator extends ContextAwareBase implements Configurator {

    @Override
    public Configurator.ExecutionStatus configure(LoggerContext ctx) {
        try {
            IFn require = Clojure.var("clojure.core","require");
            require.invoke(Clojure.read("cljdoc.server.log.init"));

            IFn configure = Clojure.var("cljdoc.server.log.init", "configure");
            configure.invoke(ctx);
        } catch (Throwable e) {
            // Make any unexpected error fatal (default is to carry on)
            e.printStackTrace();
            System.exit(1);
        }
        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
    }
}
