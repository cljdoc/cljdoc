package cljdoc.render;

import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.ParagraphContainer;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jetbrains.annotations.NotNull;

public class GitHubAlertBlock extends Block implements ParagraphContainer {
    private BasedSequence alertType = BasedSequence.NULL;

    public GitHubAlertBlock(BasedSequence alertType) {
        this.alertType = alertType;
    }

    @NotNull
    @Override
    public BasedSequence[] getSegments() {
        return new BasedSequence[] {
            // We don't have an interest of writing a formatter (to write back to md)
            // so we only capture what we need to render (to html)
            alertType
        };
    }

    public BasedSequence getAlertType() {
        return alertType;
    }

    @Override
    public boolean isParagraphEndWrappingDisabled(Paragraph node) {
        return false;
    }

    @Override
    public boolean isParagraphStartWrappingDisabled(Paragraph node) {
        return false;
    }
}
