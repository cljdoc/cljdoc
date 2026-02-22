package cljdoc.server.log;

import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.helpers.ThrowableToStringArray;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.status.StatusManager;
import ch.qos.logback.core.status.OnFileStatusListener;
import ch.qos.logback.core.util.CachingDateFormatter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.io.PrintStream;

import java.util.Iterator;
import java.util.List;

/**
 * The built-in status managers only log the time.
 * This adaptation logs the date and time.
 * Somewhat surprising how hard this was to achieve.
 * This part of logback is not easy to adapt, it is hard to reuse anything in a sensible way.
 *
 * The adaptation also ignores the "threshold" (which is time-based rather than level-based, btw).
 */
public class CustomFileStatusListener extends ContextAwareBase implements StatusListener, LifeCycle {
    private final CachingDateFormatter dateFormatter;
    private String filename;
    private PrintStream printStream;
    private boolean isStarted = false;

    public CustomFileStatusListener() {
        this.dateFormatter = new CachingDateFormatter("yyyy-MM-dd HH:mm:ss.SSS");
    }

    private void printCollectedStatus() {
       if (context == null) {
           return;
       }
       StatusManager sm = context.getStatusManager();
       List<Status> statusList = sm.getCopyOfStatusList();
       for (Status status : statusList) {
           print(status);
        }
    }

    private void print(Status status) {
        printStream.print(formatStatus(status));
    }

    @Override
    public void addStatusEvent(Status status) {
        if (isStarted()) {
            print(status);
        }
    }

    @Override
    public void start() {
        String filename = getFilename();
        if (filename == null) {
           throw new IllegalArgumentException("log filename cannot be null");
        }

        try {
            FileOutputStream fos = new FileOutputStream(filename, true);
            printStream = new PrintStream(fos, true);
        } catch (FileNotFoundException e) {
           throw new IllegalArgumentException("log filename not create-able: " + filename);
        }
        isStarted = true;
        printCollectedStatus();
    }

    @Override
    public boolean isStarted() {
        return isStarted;
    }

    @Override
    public void stop() {
        if (!isStarted()) {
            return;
        }
        if (printStream != null) {
            printStream.close();
        }
        isStarted = false;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    private StringBuilder formatStatus(Status status) {
        StringBuilder sb = new StringBuilder();
        recursiveFormatStatus(sb, "", status);
        return sb;
    }

    private void recursiveFormatStatus(StringBuilder sb, String indent, Status status) {
        String prefix = status.hasChildren() ? (indent + "+ ") : (indent + "|-");

        sb.append(dateFormatter.format(status.getTimestamp())).append(" ");
        sb.append(prefix).append(status).append("\n");

        if (status.getThrowable() != null) {
            formatThrowable(sb, status.getThrowable());
        }

        if (status.hasChildren()) {
            Iterator<Status> iter = status.iterator();
            while (iter.hasNext()) {
                recursiveFormatStatus(sb, indent + "  ", iter.next());
            }
        }
    }



    private void formatThrowable(StringBuilder sb, Throwable t) {
        String[] stringRep = ThrowableToStringArray.convert(t);

        for (String line : stringRep) {
           // Indent all lines by one tab for readability
           sb.append("\t").append(line).append(CoreConstants.LINE_SEPARATOR);
        }
    //     for (String line : stringRep) {
    //         if (line.startsWith(CoreConstants.CAUSED_BY)) {
    //             // nothing
    //         } else if (Character.isDigit(line.charAt(0))) {
    //             // if line resembles "48 common frames omitted"
    //             sb.append("\t... ");
    //         } else {
    //             // most of the time. just add a tab+"at"
    //             sb.append("\tat ");
    //         }
    //         sb.append(line).append(CoreConstants.LINE_SEPARATOR);
    //     }
     }
}
