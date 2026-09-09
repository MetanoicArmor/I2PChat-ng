#include "profile_combo.hpp"

#include "popup_chrome.hpp"
#include "rounded_scrollbar.hpp"

#include <algorithm>

#include <QApplication>
#include <QEvent>
#include <QFont>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLabel>
#include <QAbstractItemView>
#include <QLineEdit>
#include <QListWidget>
#include <QMouseEvent>
#include <QResizeEvent>
#include <QScrollBar>
#include <QScreen>
#include <QShowEvent>
#include <QTimer>
#include <QVBoxLayout>

namespace i2pchat::gui {
namespace {

constexpr int kDropWidth = 28;
constexpr qreal kOuterRadius = 12.0;

QPoint clamp_popup(const QPoint& pos, int w, int h, const QRect& avail) {
    return QPoint(std::clamp(pos.x(), avail.left(), avail.right() - w + 1),
                  std::clamp(pos.y(), avail.top(), avail.bottom() - h + 1));
}

}  // namespace

ProfileComboBox::ProfileComboBox(QWidget* parent) : QComboBox(parent) {}

void ProfileComboBox::showPopup() {
    if (QAbstractItemView* v = view()) {
        v->hide();
    }
    emit popupRequested();
}

ProfileComboPopup::ProfileComboPopup(QWidget* parent, bool as_embedded)
    : QFrame(parent), embedded_(as_embedded) {
    setObjectName("ProfileComboPopupWindow");
    setFrameShape(QFrame::NoFrame);
    if (embedded_) {
        // Non-native child of the dialog. Do not enable WA_TranslucentBackground:
        // without a layered HWND, "transparent" pixels are composited as black.
        setWindowFlags(Qt::Widget);
        setAttribute(Qt::WA_StyledBackground, true);
        setAttribute(Qt::WA_TranslucentBackground, false);
        setAttribute(Qt::WA_OpaquePaintEvent, true);
        setAttribute(Qt::WA_DontCreateNativeAncestors, true);
        setAutoFillBackground(false);
    } else {
        Qt::WindowFlags flags = Qt::Popup | Qt::FramelessWindowHint;
#ifdef Q_OS_WIN
        flags |= Qt::NoDropShadowWindowHint;
#endif
        setWindowFlags(flags);
        prepare_translucent_popup(this);
    }
    setMinimumWidth(264);
    setFocusPolicy(Qt::NoFocus);

    auto* root = new QVBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);

    surface_ = new QFrame(this);
    surface_->setObjectName("ProfileComboPopupSurface");
    surface_->setFrameShape(QFrame::NoFrame);
    if (embedded_) {
        surface_->setAutoFillBackground(false);
        surface_->setAttribute(Qt::WA_TranslucentBackground, false);
    } else {
        prepare_translucent_popup(surface_);
    }
    root->addWidget(surface_);

    auto* inner = new QHBoxLayout(surface_);
    if (embedded_) {
        inner->setContentsMargins(6, 5, 6, 5);
    } else {
        inner->setContentsMargins(10, 12, 10, 12);
    }
    inner->setSpacing(0);

    list_ = new QListWidget(surface_);
    list_->setObjectName("ProfileComboPopupList");
    list_->setFrameShape(QFrame::NoFrame);
    list_->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    list_->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    list_->setSpacing(4);
    list_->setUniformItemSizes(true);
    if (embedded_) {
        list_->setAutoFillBackground(false);
        list_->viewport()->setAutoFillBackground(false);
    } else {
        prepare_translucent_popup(list_);
        prepare_translucent_popup(list_->viewport());
        make_widget_palette_transparent(list_);
        make_widget_palette_transparent(list_->viewport());
    }
    inner->addWidget(list_, 1);

    scrollbar_ = new RoundedVerticalScrollbar(list_->verticalScrollBar(), surface_);
    inner->addWidget(scrollbar_, 0);

    connect(list_, &QListWidget::itemClicked, this, [this](QListWidgetItem* item) {
        if (item != nullptr) {
            emit itemChosen(item->text());
            hide();
        }
    });
    connect(list_, &QListWidget::itemActivated, this, [this](QListWidgetItem* item) {
        if (item != nullptr) {
            emit itemChosen(item->text());
            hide();
        }
    });
    apply_theme(false);
}

void ProfileComboPopup::apply_theme(bool night) {
    if (night) {
        bg_ = QColor(28, 31, 40);
        border_ = QColor(58, 62, 74);
        behind_ = QColor(0x14, 0x14, 0x17);
        scrollbar_->set_colors(QColor(255, 255, 255, 51), QColor(0, 0, 0, 0));
        list_->setStyleSheet(QStringLiteral(
            "QListWidget#ProfileComboPopupList {"
            "  background: transparent; border: none; outline: none;"
            "  color: #d8deea; font-size: 13px;"
            "}"
            "QListWidget#ProfileComboPopupList::item {"
            "  border-radius: 6px; padding: 4px 10px; margin: 0px 2px 4px 2px; border: none;"
            "}"
            "QListWidget#ProfileComboPopupList::item:selected,"
            "QListWidget#ProfileComboPopupList::item:selected:active {"
            "  background: #3a5588; color: #f4f7ff;"
            "}"
            "QListWidget#ProfileComboPopupList::item:hover { background: #2c3039; }"));
    } else {
        bg_ = QColor(246, 247, 250);
        border_ = QColor(208, 211, 218);
        behind_ = QColor(0xf5, 0xf5, 0xf7);
        scrollbar_->set_colors(QColor(60, 60, 67, 72), QColor(0, 0, 0, 0));
        list_->setStyleSheet(QStringLiteral(
            "QListWidget#ProfileComboPopupList {"
            "  background: transparent; border: none; outline: none;"
            "  color: #2f3644; font-size: 13px;"
            "}"
            "QListWidget#ProfileComboPopupList::item {"
            "  border-radius: 6px; padding: 4px 10px; margin: 0px 2px 4px 2px; border: none;"
            "}"
            "QListWidget#ProfileComboPopupList::item:selected,"
            "QListWidget#ProfileComboPopupList::item:selected:active {"
            "  background: #dbe9ff; color: #1b4f9f;"
            "}"
            "QListWidget#ProfileComboPopupList::item:hover { background: #e8eef8; }"));
    }
    if (embedded_) {
        setStyleSheet(QStringLiteral(
            "#ProfileComboPopupWindow { background: none; border: none; }"
            "#ProfileComboPopupSurface { background: none; border: none; }"));
    } else {
        setStyleSheet(QStringLiteral(
            "#ProfileComboPopupWindow { background: transparent; border: none; }"
            "#ProfileComboPopupSurface { background: transparent; border: none; }"));
    }
    update();
}

void ProfileComboPopup::set_items(const QStringList& values, const QString& selected) {
    list_->clear();
    int selected_row = -1;
    for (const QString& raw : values) {
        const QString text = raw.trimmed();
        if (text.isEmpty()) {
            continue;
        }
        auto* item = new QListWidgetItem(text, list_);
        item->setTextAlignment(Qt::AlignLeft | Qt::AlignVCenter);
        if (text == selected) {
            selected_row = list_->count() - 1;
        }
    }
    if (selected_row >= 0) {
        list_->setCurrentRow(selected_row);
    } else if (list_->count() > 0) {
        list_->setCurrentRow(0);
    }
}

void ProfileComboPopup::sync_scrollbar() {
    const int n = list_->count();
    QScrollBar* vsb = list_->verticalScrollBar();
    scrollbar_->setVisible(n > 1 && vsb != nullptr && vsb->maximum() > 0);
    scrollbar_->update();
}

void ProfileComboPopup::size_to_anchor(QWidget* anchor) {
    const int n = list_->count();
    const int spacing = std::max(0, list_->spacing());
    list_->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);

    const QMargins m = surface_->layout() != nullptr ? surface_->layout()->contentsMargins()
                                                     : QMargins();
    if (n <= 0) {
        list_->setFixedHeight(1);
        scrollbar_->setFixedHeight(1);
        setFixedWidth(std::max(anchor->width(), minimumWidth()));
        setFixedHeight(m.top() + 1 + m.bottom());
        scrollbar_->setVisible(false);
        return;
    }

    const int visible = std::min(n, 8);
    int list_h = 1;
    for (int i = 0; i < visible; ++i) {
        int h = list_->sizeHintForRow(i);
        if (h < 0) {
            h = 24;
        }
        list_h += std::clamp(h, 24, 34);
        if (i + 1 < visible) {
            list_h += spacing;
        }
    }
    list_->setFixedHeight(list_h);
    scrollbar_->setFixedHeight(list_h);
    setFixedWidth(std::max(anchor->width(), minimumWidth()));
    setFixedHeight(m.top() + list_h + m.bottom());
    list_->updateGeometry();
    sync_scrollbar();
}

void ProfileComboPopup::show_below(QWidget* anchor, bool keep_editor_focus) {
    setAttribute(Qt::WA_ShowWithoutActivating, keep_editor_focus);
    list_->setFocusPolicy(keep_editor_focus ? Qt::NoFocus : Qt::StrongFocus);

    if (embedded_) {
        if (QWidget* win = anchor->window()) {
            if (parentWidget() != win) {
                setParent(win);
            }
        }
        size_to_anchor(anchor);
        const QPoint local = anchor->mapTo(parentWidget(), QPoint(0, anchor->height() + 4));
        move(local);
        show();
        raise();
        sync_scrollbar();
        return;
    }

    size_to_anchor(anchor);
    QPoint pos = anchor->mapToGlobal(QPoint(0, anchor->height() + 4));
    if (QScreen* screen = QApplication::screenAt(anchor->mapToGlobal(anchor->rect().center()))) {
        pos = clamp_popup(pos, width(), height(), screen->availableGeometry());
    }
    move(pos);
    show();
    sync_scrollbar();
    QTimer::singleShot(0, this, [this, anchor] {
        if (!isVisible()) {
            return;
        }
        size_to_anchor(anchor);
        QPoint next = anchor->mapToGlobal(QPoint(0, anchor->height() + 4));
        if (QScreen* screen = QApplication::screenAt(next)) {
            next = clamp_popup(next, width(), height(), screen->availableGeometry());
        }
        move(next);
        sync_scrollbar();
    });
    if (!keep_editor_focus) {
        QTimer::singleShot(0, list_, [this] { list_->setFocus(); });
    }
}

void ProfileComboPopup::paintEvent(QPaintEvent*) {
    if (embedded_) {
        paint_rounded_popup_on_solid(this, bg_, border_, kOuterRadius, behind_);
        return;
    }
    paint_rounded_popup_bg(this, bg_, border_, kOuterRadius);
}

void ProfileComboPopup::showEvent(QShowEvent* event) {
    QFrame::showEvent(event);
    list_->viewport()->setAutoFillBackground(false);
    if (!embedded_ && !dwm_patched_) {
        disable_dwm_rounded_frame(this);
        dwm_patched_ = true;
    }
}

void ProfileComboPopup::resizeEvent(QResizeEvent* event) {
    QFrame::resizeEvent(event);
}

ProfileComboWithArrow::ProfileComboWithArrow(QWidget* parent) : QWidget(parent) {
    setObjectName("ProfileComboRow");
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Preferred);

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(0);

    combo_ = new ProfileComboBox(this);
    combo_->setEditable(true);
    combo_->setInsertPolicy(QComboBox::NoInsert);
    combo_->setCompleter(nullptr);
    layout->addWidget(combo_);

    arrow_ = new QLabel(QStringLiteral("∨"), this);
    arrow_->setObjectName(QStringLiteral("ProfileComboArrow"));
    arrow_->setAlignment(Qt::AlignCenter);
    arrow_->setAttribute(Qt::WA_TransparentForMouseEvents, true);
    arrow_->setFocusPolicy(Qt::NoFocus);
    QFont arrow_font = QApplication::font();
    arrow_font.setPixelSize(10);
    arrow_->setFont(arrow_font);
    set_arrow_color(QStringLiteral("#9fa1b5"));
    arrow_->raise();

#ifdef Q_OS_WIN
    // Top-level Qt::Popup HWND + QListWidget still leaves black DWM corners.
    // Draw the list as a child of the dialog instead (same as typing suggestions).
    popup_ = new ProfileComboPopup(nullptr, true);
#else
    popup_ = new ProfileComboPopup(this, false);
#endif
    typing_panel_ = new ProfileComboPopup(nullptr, true);
    qApp->installEventFilter(this);
    connect(combo_, &ProfileComboBox::popupRequested, this,
            &ProfileComboWithArrow::show_custom_popup);
    connect(popup_, &ProfileComboPopup::itemChosen, this, &ProfileComboWithArrow::on_item_chosen);
    connect(typing_panel_, &ProfileComboPopup::itemChosen, this,
            &ProfileComboWithArrow::on_item_chosen);
}

void ProfileComboWithArrow::set_arrow_color(const QString& color) {
    arrow_->setStyleSheet(
        QStringLiteral("QLabel#ProfileComboArrow { color: %1; font-size: 10px; "
                       "background: transparent; border: none; }")
            .arg(color));
}

void ProfileComboWithArrow::apply_theme(bool night) {
    set_arrow_color(night ? QStringLiteral("#9fa1b5") : QStringLiteral("#8c8d94"));
    popup_->apply_theme(night);
    typing_panel_->apply_theme(night);
}

void ProfileComboWithArrow::enable_autocomplete() {
    combo_->setCompleter(nullptr);
    typing_enabled_ = true;
    if (QLineEdit* le = combo_->lineEdit()) {
        connect(le, &QLineEdit::textChanged, this, &ProfileComboWithArrow::on_typing,
                Qt::UniqueConnection);
        le->installEventFilter(this);
    }
    combo_->installEventFilter(this);
}

void ProfileComboWithArrow::show_custom_popup() {
    typing_panel_->hide();
    popup_->set_items(unique_values(), combo_->currentText().trimmed());
    popup_->show_below(combo_, false);
}

void ProfileComboWithArrow::on_item_chosen(const QString& text) {
    suppress_suggest_ = true;
    const int idx = combo_->findText(text);
    if (idx >= 0) {
        combo_->setCurrentIndex(idx);
    } else {
        combo_->setCurrentText(text);
    }
    popup_->hide();
    typing_panel_->hide();
    suppress_suggest_ = false;
}

void ProfileComboWithArrow::on_typing(const QString& text) {
    if (!typing_enabled_ || suppress_suggest_) {
        return;
    }
    const QStringList matches = filtered_suggestions(text.trimmed());
    if (matches.isEmpty()) {
        typing_panel_->hide();
        return;
    }
    popup_->hide();
    typing_panel_->set_items(matches, text.trimmed());
    typing_panel_->show_below(combo_, true);
}

void ProfileComboWithArrow::hide_suggest_if_focus_left() {
    QWidget* fw = QApplication::focusWidget();
    if (fw != nullptr &&
        (fw == popup_ || popup_->isAncestorOf(fw) || fw == typing_panel_ ||
         typing_panel_->isAncestorOf(fw))) {
        return;
    }
    typing_panel_->hide();
    popup_->hide();
}

QStringList ProfileComboWithArrow::unique_values() const {
    QStringList out;
    QStringList seen;
    for (int i = 0; i < combo_->count(); ++i) {
        const QString text = combo_->itemText(i).trimmed();
        if (text.isEmpty() || seen.contains(text)) {
            continue;
        }
        seen.append(text);
        out.append(text);
    }
    return out;
}

QStringList ProfileComboWithArrow::filtered_suggestions(const QString& needle) const {
    if (needle.isEmpty()) {
        return {};
    }
    const QString nl = needle.toLower();
    QStringList out;
    for (const QString& value : unique_values()) {
        if (value.toLower().startsWith(nl)) {
            out.append(value);
        }
    }
    std::sort(out.begin(), out.end(),
              [](const QString& a, const QString& b) { return a.toLower() < b.toLower(); });
    return out;
}

QListWidget* ProfileComboWithArrow::list_for_editor_keys() const {
    if (typing_panel_->isVisible() && typing_panel_->list()->count() > 0) {
        return typing_panel_->list();
    }
    if (popup_->isVisible() && popup_->list()->count() > 0) {
        return popup_->list();
    }
    return nullptr;
}

bool ProfileComboWithArrow::handle_list_key(QListWidget* list, int key) {
    const int n = list->count();
    if (n <= 0) {
        return false;
    }
    if (key == Qt::Key_Down || key == Qt::Key_Up) {
        int row = list->currentRow();
        if (key == Qt::Key_Down) {
            row = row < 0 ? 0 : std::min(row + 1, n - 1);
        } else {
            row = row < 0 ? n - 1 : std::max(row - 1, 0);
        }
        list->setCurrentRow(row);
        return true;
    }
    if (key == Qt::Key_Return || key == Qt::Key_Enter) {
        QListWidgetItem* item = list->currentItem();
        if (item == nullptr) {
            list->setCurrentRow(0);
            item = list->currentItem();
        }
        if (item != nullptr) {
            on_item_chosen(item->text());
        }
        return true;
    }
    return false;
}

bool ProfileComboWithArrow::eventFilter(QObject* watched, QEvent* event) {
    if (event->type() == QEvent::MouseButtonPress) {
        const auto* mouse = static_cast<const QMouseEvent*>(event);
        const QPoint g = mouse->globalPosition().toPoint();
        auto contains_global = [&](QWidget* w) {
            return w != nullptr && w->isVisible() &&
                   w->rect().contains(w->mapFromGlobal(g));
        };
        if (popup_->isVisible() && !contains_global(popup_) && !contains_global(this)) {
            popup_->hide();
        }
        if (typing_panel_->isVisible() && !contains_global(typing_panel_) &&
            !contains_global(this)) {
            typing_panel_->hide();
        }
    }
    QLineEdit* le = combo_->lineEdit();
    if (le == nullptr || (watched != le && watched != combo_)) {
        return false;
    }
    if (watched == le && event->type() == QEvent::FocusOut) {
        QTimer::singleShot(0, this, &ProfileComboWithArrow::hide_suggest_if_focus_left);
        return false;
    }
    if (event->type() == QEvent::KeyPress) {
        auto* key = static_cast<QKeyEvent*>(event);
        if (QListWidget* list = list_for_editor_keys()) {
            if (handle_list_key(list, key->key())) {
                return true;
            }
        }
        if (key->key() == Qt::Key_Escape) {
            if (typing_panel_->isVisible()) {
                typing_panel_->hide();
                return true;
            }
            if (popup_->isVisible()) {
                popup_->hide();
                return true;
            }
        }
    }
    return false;
}

void ProfileComboWithArrow::resizeEvent(QResizeEvent* event) {
    QWidget::resizeEvent(event);
    arrow_->setGeometry(width() - kDropWidth, 0, kDropWidth, height());
    arrow_->raise();
}

}  // namespace i2pchat::gui
