#include "emoji_picker.hpp"
#include "emoji_chars.hpp"
#include "popup_chrome.hpp"
#include "rounded_scrollbar.hpp"

#include <algorithm>
#include <cmath>
#include <QApplication>
#include <QColor>
#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QIcon>
#include <QHideEvent>
#include <QKeyEvent>
#include <QPainter>
#include <QPixmap>
#include <QScreen>
#include <QScrollArea>
#include <QScrollBar>
#include <QShowEvent>
#include <QStyle>
#include <QToolButton>
#include <QVBoxLayout>
#include <fstream>

#include <nlohmann/json.hpp>

namespace i2pchat::gui {
namespace {

constexpr int kCols = 8;
constexpr int kCell = 36;

QIcon pixmap_icon(const QPixmap& src, int logical) {
    if (src.isNull()) {
        return {};
    }
    qreal dpr = 1.0;
    if (QScreen* screen = QApplication::primaryScreen()) {
        dpr = std::max(1.0, std::min(3.0, screen->devicePixelRatio()));
    }
    const int phys = std::max(1, static_cast<int>(std::lround(logical * dpr)));
    QPixmap canvas(phys, phys);
    canvas.fill(Qt::transparent);
    const QPixmap scaled =
        src.scaled(phys, phys, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    QPainter p(&canvas);
    p.drawPixmap((phys - scaled.width()) / 2, (phys - scaled.height()) / 2, scaled);
    p.end();
    canvas.setDevicePixelRatio(dpr);
    return QIcon(canvas);
}

QPixmap tint_alpha(const QPixmap& source, const QColor& color) {
    if (source.isNull()) {
        return source;
    }
    QPixmap work = source;
    work.setDevicePixelRatio(1.0);
    QPixmap out(work.size());
    out.fill(Qt::transparent);
    QPainter p(&out);
    p.fillRect(out.rect(), color);
    p.setCompositionMode(QPainter::CompositionMode_DestinationIn);
    p.drawPixmap(0, 0, work);
    p.end();
    out.setDevicePixelRatio(source.devicePixelRatio());
    return out;
}

}  // namespace

std::filesystem::path find_fluent_emoji_root() {
    const QDir exe(QCoreApplication::applicationDirPath());
    const QStringList rels = {
        QStringLiteral("../Resources/fluent_emoji"),
        QStringLiteral("../../Resources/fluent_emoji"),
        QStringLiteral("../../../../i2pchat/gui/fluent_emoji"),
        QStringLiteral("../../../i2pchat/gui/fluent_emoji"),
        QStringLiteral("../../../../../i2pchat/gui/fluent_emoji"),
        QStringLiteral("../i2pchat/gui/fluent_emoji"),
    };
    for (const QString& rel : rels) {
        const QDir dir(QDir::cleanPath(exe.absoluteFilePath(rel)));
        if (QFile::exists(dir.filePath(QStringLiteral("manifest.json")))) {
            return dir.absolutePath().toStdString();
        }
    }
    return {};
}

QIcon tinted_face_icon(bool dark) {
    const QDir exe(QCoreApplication::applicationDirPath());
    const QStringList rels = {
        QStringLiteral(":/i2pchat/icons/face.dashed.png"),
        exe.absoluteFilePath(QStringLiteral("../Resources/icons/face.dashed.png")),
        exe.absoluteFilePath(QStringLiteral("../../../../i2pchat/gui/icons/face.dashed.png")),
        exe.absoluteFilePath(QStringLiteral("../../../i2pchat/gui/icons/face.dashed.png")),
        exe.absoluteFilePath(QStringLiteral("../../../../../i2pchat/gui/icons/face.dashed.png")),
    };
    QPixmap pm;
    for (const QString& path : rels) {
        pm = QPixmap(path);
        if (!pm.isNull()) {
            break;
        }
    }
    if (pm.isNull()) {
        return {};
    }
    const QColor color = dark ? QColor(245, 245, 247, 140) : QColor(60, 60, 67, 140);
    return QIcon(tint_alpha(pm, color));
}

EmojiPickerPopup::EmojiPickerPopup(QWidget* parent) : QFrame(parent) {
    setObjectName("EmojiPickerPopupWindow");
    setWindowFlags(Qt::Popup | Qt::FramelessWindowHint | Qt::NoDropShadowWindowHint);
    setAttribute(Qt::WA_TranslucentBackground, true);
    setFocusPolicy(Qt::StrongFocus);
    auto* root = new QVBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);
    auto* surface = new QFrame(this);
    surface->setObjectName("EmojiPickerPopupSurface");
    surface->setAttribute(Qt::WA_TranslucentBackground, true);
    surface->setFocusPolicy(Qt::NoFocus);
    root->addWidget(surface);
    auto* lay = new QVBoxLayout(surface);
    lay->setContentsMargins(6, 6, 6, 6);
    lay->setSpacing(0);
    scroll_ = new QScrollArea(surface);
    scroll_->setObjectName("EmojiPickerScroll");
    scroll_->setWidgetResizable(true);
    scroll_->setFrameShape(QFrame::NoFrame);
    scroll_->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroll_->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroll_->setFixedHeight(260);
    scroll_->setFocusPolicy(Qt::NoFocus);
    inner_ = new QWidget();
    inner_->setObjectName("EmojiPickerGridHost");
    inner_->setFocusPolicy(Qt::NoFocus);
    scroll_->setWidget(inner_);
    scrollbar_ = new RoundedVerticalScrollbar(scroll_->verticalScrollBar(), surface);
    auto* scroll_row = new QHBoxLayout();
    scroll_row->setContentsMargins(0, 0, 0, 0);
    scroll_row->setSpacing(4);
    scroll_row->addWidget(scroll_, 1);
    scroll_row->addWidget(scrollbar_, 0);
    lay->addLayout(scroll_row);
    connect(scroll_->verticalScrollBar(), &QScrollBar::rangeChanged, this,
            [this](int, int) { sync_scrollbar(); });
    setFixedWidth(std::min(kCols * 44 + 34, 392));
    root_ = find_fluent_emoji_root();
    if (!root_.empty()) {
        std::ifstream in(root_ / "manifest.json");
        nlohmann::json doc = nlohmann::json::parse(in, nullptr, false);
        if (doc.is_object()) {
            for (auto it = doc.begin(); it != doc.end(); ++it) {
                if (it.value().is_string()) {
                    png_by_glyph_.insert(QString::fromStdString(it.key()),
                                         QString::fromStdString(it.value().get<std::string>()));
                }
            }
        }
    }
    rebuild();
    apply_theme();
}

void EmojiPickerPopup::rebuild() {
    auto* grid = new QGridLayout(inner_);
    grid->setSpacing(4);
    grid->setContentsMargins(8, 6, 8, 6);
    buttons_.clear();
    int i = 0;
    for (const std::string_view raw : kEmojiChars) {
        const QString glyph = QString::fromUtf8(raw.data(), static_cast<int>(raw.size()));
        auto* btn = new QToolButton(inner_);
        btn->setObjectName("EmojiCell");
        btn->setAutoRaise(true);
        btn->setFocusPolicy(Qt::NoFocus);
        btn->setFixedSize(kCell, kCell);
        btn->setToolTip(glyph);
        QIcon icon;
        const auto it = png_by_glyph_.constFind(glyph);
        if (it != png_by_glyph_.cend() && !root_.empty()) {
            const QPixmap pm(QString::fromStdString((root_ / it->toStdString()).string()));
            icon = pixmap_icon(pm, 28);
        }
        if (!icon.isNull()) {
            btn->setIcon(icon);
            btn->setIconSize(QSize(28, 28));
        } else {
            btn->setText(glyph);
        }
        connect(btn, &QToolButton::clicked, this, [this, glyph] {
            emit emoji_chosen(glyph);
            hide();
        });
        grid->addWidget(btn, i / kCols, i % kCols);
        buttons_.push_back(btn);
        ++i;
    }
    sync_scrollbar();
}

void EmojiPickerPopup::sync_scrollbar() {
    if (scrollbar_ == nullptr || scroll_ == nullptr) {
        return;
    }
    QScrollBar* vsb = scroll_->verticalScrollBar();
    scrollbar_->setVisible(vsb != nullptr && vsb->maximum() > 0);
    scrollbar_->update();
}

void EmojiPickerPopup::apply_theme() {
    if (night_) {
        popup_bg_ = QColor(34, 37, 45, 244);
        popup_border_ = QColor(58, 62, 74);
        scrollbar_->set_colors(QColor(255, 255, 255, 51), QColor(0, 0, 0, 0));
    } else {
        popup_bg_ = QColor(246, 247, 250);
        popup_border_ = QColor(208, 211, 218);
        scrollbar_->set_colors(QColor(60, 60, 67, 72), QColor(0, 0, 0, 0));
    }
    setStyleSheet(QStringLiteral(
        "QFrame#EmojiPickerPopupWindow { background: transparent; border: none; }"
        "QFrame#EmojiPickerPopupSurface { background: transparent; border: none; }"
        "QScrollArea#EmojiPickerScroll { border: none; background: transparent; }"
        "QWidget#EmojiPickerGridHost { background: transparent; }"));
    sync_scrollbar();
    update();
}

void EmojiPickerPopup::sync_focus_visual() {
    const int n = static_cast<int>(buttons_.size());
    if (n == 0) {
        return;
    }
    focus_idx_ = std::clamp(focus_idx_, 0, n - 1);
    for (int i = 0; i < n; ++i) {
        buttons_[i]->setProperty("emojiNavFocus", i == focus_idx_);
        buttons_[i]->style()->unpolish(buttons_[i]);
        buttons_[i]->style()->polish(buttons_[i]);
    }
    scroll_->ensureWidgetVisible(buttons_[focus_idx_]);
}

void EmojiPickerPopup::pick_focused() {
    if (focus_idx_ < 0 || focus_idx_ >= buttons_.size()) {
        return;
    }
    const std::string_view raw = kEmojiChars[static_cast<std::size_t>(focus_idx_)];
    emit emoji_chosen(QString::fromUtf8(raw.data(), static_cast<int>(raw.size())));
    hide();
}

void EmojiPickerPopup::keyPressEvent(QKeyEvent* event) {
    const int n = static_cast<int>(buttons_.size());
    if (n == 0) {
        QFrame::keyPressEvent(event);
        return;
    }
    const int key = event->key();
    if (key == Qt::Key_Escape) {
        hide();
        event->accept();
        return;
    }
    if (key == Qt::Key_Return || key == Qt::Key_Enter || key == Qt::Key_Space) {
        pick_focused();
        event->accept();
        return;
    }
    const int cols = kCols;
    const int row = focus_idx_ / cols;
    const int col = focus_idx_ % cols;
    bool moved = false;
    if (key == Qt::Key_Left && col > 0) {
        --focus_idx_;
        moved = true;
    } else if (key == Qt::Key_Right && col < cols - 1 && focus_idx_ + 1 < n) {
        ++focus_idx_;
        moved = true;
    } else if (key == Qt::Key_Up && focus_idx_ >= cols) {
        focus_idx_ -= cols;
        moved = true;
    } else if (key == Qt::Key_Down && focus_idx_ + cols < n) {
        focus_idx_ += cols;
        moved = true;
    }
    (void)row;
    if (moved) {
        sync_focus_visual();
        event->accept();
        return;
    }
    QFrame::keyPressEvent(event);
}

void EmojiPickerPopup::set_night(bool night) {
    night_ = night;
    apply_theme();
}

void EmojiPickerPopup::paintEvent(QPaintEvent*) {
    paint_rounded_popup_bg(this, popup_bg_, popup_border_, 14.0);
}

void EmojiPickerPopup::showEvent(QShowEvent* event) {
    QFrame::showEvent(event);
    if (!dwm_patched_) {
        disable_dwm_rounded_frame(this);
        dwm_patched_ = true;
    }
    sync_scrollbar();
}

void EmojiPickerPopup::show_above(QWidget* anchor) {
    adjustSize();
    const QPoint top_left = anchor->mapToGlobal(QPoint(0, 0));
    int x = top_left.x() + anchor->width() - width();
    int y = top_left.y() - height() - 6;
    if (y < 0) {
        y = top_left.y() + anchor->height() + 6;
    }
    if (QScreen* screen = QApplication::screenAt(QPoint(x, y))) {
        const QRect avail = screen->availableGeometry();
        constexpr int margin = 6;
        x = std::max(avail.left() + margin, std::min(x, avail.right() - width() - margin + 1));
        y = std::max(avail.top() + margin, std::min(y, avail.bottom() - height() - margin + 1));
    }
    move(x, y);
    focus_idx_ = 0;
    sync_focus_visual();
    show();
    raise();
    activateWindow();
    setFocus(Qt::PopupFocusReason);
}

void EmojiPickerPopup::hideEvent(QHideEvent* event) {
    QFrame::hideEvent(event);
    emit picker_hidden();
}

}  // namespace i2pchat::gui
