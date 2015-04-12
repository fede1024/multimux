import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Component;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import javax.swing.JSplitPane;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

public class JMultimuxSplit extends JSplitPane {
    private int dividerDragSize = 9;

    private int xTick = 1, yTick = 1;

    public JMultimuxSplit() {
        this(HORIZONTAL_SPLIT);
    }

    public JMultimuxSplit(int orientation) {
        super(orientation);
        setContinuousLayout(true);
        setDividerSize(10);
    }

    public JMultimuxSplit (int orientation, Component newLeftComponent, Component newRightComponent, int xTick, int yTick) {
        super(orientation);
        setContinuousLayout(true);
        setLeftComponent(newLeftComponent);
        setRightComponent(newRightComponent);
        this.xTick = xTick;
        this.yTick = yTick;
        if (orientation == HORIZONTAL_SPLIT){
            setDividerSize(xTick);
            setDividerDragSize(xTick);
        }
        else {
            setDividerSize(yTick);
            setDividerDragSize(yTick);
        }
    }

    public int getDividerDragSize() {
        return dividerDragSize;
    }

    public void setDividerDragSize(int dividerDragSize) {
        this.dividerDragSize = dividerDragSize;
        revalidate();
    }

    @Override
    public void setDividerLocation(int location){
        if(orientation == HORIZONTAL_SPLIT)
            super.setDividerLocation((location/xTick)*xTick);
        else
            super.setDividerLocation((location/yTick)*yTick);
    }

    @Override
    public void updateUI() {
        setUI(new MultimuxSplitUI());
        revalidate();
    }

    private class MultimuxSplitUI extends BasicSplitPaneUI {
        public MultimuxSplitUI() {
            super();
        }

        @Override
        public BasicSplitPaneDivider createDefaultDivider() {
            return new MultimuxSplitDivider(this);
        }
    }

    private class MultimuxSplitDivider extends BasicSplitPaneDivider {

        public MultimuxSplitDivider(BasicSplitPaneUI ui) {
            super(ui);
            super.setBorder(null);
            setBackground(UIManager.getColor("controlShadow"));
        }

        @Override
        public void setBorder(Border border) {
            // ignore
        }

        @Override
        public void paint( Graphics g ) {
            int dividerSize = getDividerSize();
            int height = getHeight();
            int width = getWidth();
            Graphics2D g2 = (Graphics2D) g;
            g2.setStroke(new BasicStroke(3));
            if(orientation == HORIZONTAL_SPLIT) {
                g2.setColor(UIManager.getColor("SplitDivider.background"));
                g2.fillRect(0, 0, dividerSize, height);
                g2.setColor(UIManager.getColor("SplitDivider.foreground"));
                g2.drawLine(dividerSize / 2, 0, dividerSize / 2, height - 1 );
            }
            else {
                g2.setColor(UIManager.getColor("SplitDivider.background"));
                g2.fillRect(0, 0, width, height);
                g2.setColor(UIManager.getColor("SplitDivider.foreground"));
                g2.drawLine(0, dividerSize / 2, width - 1, dividerSize / 2);
            }
        }

        @Override
        protected void dragDividerTo(int location) {
            if(orientation == HORIZONTAL_SPLIT)
                super.dragDividerTo(location);
            else
                super.dragDividerTo(location);
        }

        @Override
        protected void finishDraggingTo(int location) {
            super.finishDraggingTo(location);
        }
    }
}

