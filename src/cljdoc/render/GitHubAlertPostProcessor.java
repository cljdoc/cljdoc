package cljdoc.render;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessorFactory;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flexmark post processor to support GitHub Alerts extension.
 *
 * GitHub alerts are a syntactic superset of block quotes:
 *
 * > [!TIP]
 * > I am an alert
 *
 * To be an alert, the alert must have some content:
 * >
 * >
 * > [!NOTE]
 * >
 * >
 * > I am also an alert
 *
 * But this is not an alert:
 * > [!CAUTION]
 *
 * Like block quotes, alerts can contain other types of content, but
 * some experimentation led me to believe that cannot be nested.
 *
 * We chose a post processor to let Flexmark do the parsing of block quotes;
 * we just convert what looks like an alert to an alert.
 */

public class GitHubAlertPostProcessor extends NodePostProcessor {
    private static final Pattern alertTypePattern = Pattern.compile("\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]");

    private boolean anySibHasContent(@NotNull Node node) {
       return node.getNextAnyNot(HardLineBreak.class, SoftLineBreak.class) != null;
    }

    private boolean hasEnoughContent(Node blockQuote) {
        Node firstParagraph = blockQuote.getFirstChild();
        return firstParagraph != null && anySibHasContent(firstParagraph)
            || firstParagraph != null && firstParagraph.getFirstChild() != null && anySibHasContent(firstParagraph.getFirstChild());
    }

    private BasedSequence alertType(@NotNull Node blockQuote) {
        BasedSequence alertType = null;

        if (blockQuote.getParent() instanceof Document
         && hasEnoughContent(blockQuote)
         && blockQuote.getFirstChild() instanceof Paragraph firstParagraph) {

            Node node = firstParagraph.getFirstChild();
            if (node != null) {
                Integer colOffset = node.lineColumnAtStart().getSecond();
                if (colOffset <= 5) {
                    BasedSequence alertTypeText = node.getChars();
                    Matcher matcher = alertTypePattern.matcher(alertTypeText);
                    if (matcher.find()) {
                        alertType = alertTypeText.subSequence(matcher.start(1), matcher.end(1));
                    }
                }
            }
        }
        return alertType;
    }
    
    private static void appendNodes(GitHubAlertBlock alertBlock, Node nodeIter) {
        while (nodeIter != null) {
            Node nextNode = nodeIter.getNext();
            alertBlock.appendChild(nodeIter);
            nodeIter = nextNode;
        }
    }

    private static void modifyAndPrependParagraph(GitHubAlertBlock alertBlock, Node firstParagraph) {
        // Skip over [!<alertType>] line in firstParagraph
        Node firstParaIter = firstParagraph.getFirstChild();
        while (firstParaIter != null) {
            Node nextNode = firstParaIter.getNext();
             firstParaIter.unlink();
            if (firstParaIter instanceof SoftLineBreak || firstParaIter instanceof HardLineBreak) {
                break;
            }
            firstParaIter = nextNode;
        }
        alertBlock.prependChild(firstParagraph);
    }

    private GitHubAlertBlock createGithubAlertBlock(BlockQuote blockQuote, BasedSequence alertType) {
        GitHubAlertBlock alertBlock = new GitHubAlertBlock(alertType);
        Node firstParagraph = blockQuote.getFirstChild();
        if (firstParagraph != null && anySibHasContent(firstParagraph)) {
            appendNodes(alertBlock, firstParagraph.getNext());
        }
        if (firstParagraph != null && firstParagraph.getFirstChild() != null
         && anySibHasContent(firstParagraph.getFirstChild())) {
            modifyAndPrependParagraph(alertBlock, firstParagraph);
        }
        return alertBlock;
    }

    @Override
    public void process(@NotNull NodeTracker state, @NotNull Node node) {
        if (node instanceof BlockQuote blockQuote
         && blockQuote.getFirstChild() instanceof Paragraph) {
            BasedSequence alertType = alertType(blockQuote);
            if (alertType != null) {
                GitHubAlertBlock alertBlock = createGithubAlertBlock(blockQuote, alertType);

                // Replace blockQuote with alertBlock
                blockQuote.insertBefore(alertBlock);
                blockQuote.unlink();

                // Update the node tracker
                state.nodeRemovedWithChildren(blockQuote);
                state.nodeAddedWithChildren(alertBlock);
            }
        }
    }

    public static class Factory extends NodePostProcessorFactory {
        @SuppressWarnings("this-escape") // jdk21 has new linter, I *think* we are ok here?
        public Factory() {
            super(false);
            addNodes(BlockQuote.class);
        }

        @Override
        public @NotNull NodePostProcessor apply(@NotNull Document document) {
            return new GitHubAlertPostProcessor();
        }
    }
}
