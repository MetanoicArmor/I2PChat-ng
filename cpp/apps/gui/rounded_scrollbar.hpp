#pragma once

#include <QColor>
#include <QWidget>

class QScrollBar;

namespace i2pchat::gui {

class RoundedVerticalScrollbar : public QWidget {
    Q_OBJECT
public:
    explicit RoundedVerticalScrollbar(QScrollBar* linked, QWidget* parent = nullptr);

    void set_colors(const QColor& thumb, const QColor& track);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mouseReleaseEvent(QMouseEvent* event) override;
    void wheelEvent(QWheelEvent* event) override;

private:
    [[nodiscard]] QRectF compute_thumb() const;
    void set_value_from_thumb_y(qreal thumb_y, qreal thumb_h);

    QScrollBar* sb_ = nullptr;
    QColor thumb_{60, 60, 67, 72};
    QColor track_{0, 0, 0, 0};
    bool dragging_ = false;
    int drag_offset_y_ = 0;
};

}  // namespace i2pchat::gui
