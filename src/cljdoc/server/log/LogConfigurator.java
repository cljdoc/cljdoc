package cljdoc.server.log;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.spi.ContextAwareBase;


// invoked as service via resources/META-INF/services/ch.qos.logback.classic.spi.Configurator
public class LogConfigurator extends ContextAwareBase implements Configurator {

    // TODO: fatal error if this fails
    @Override
    public Configurator.ExecutionStatus configure(LoggerContext ctx) {
        System.out.println("Hello from logback configurator");

        IFn require = Clojure.var("clojure.core","require");
        require.invoke(Clojure.read("cljdoc.server.log.init"));

        IFn configure = Clojure.var("cljdoc.server.log.init", "configure");
        configure.invoke(ctx);

        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
    }

}
