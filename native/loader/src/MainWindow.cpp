#include "MainWindow.h"

#include "InjectionOverlay.h"
#include "InstanceList.h"
#include "TitleBar.h"
#include "loader.h"
#include "theme.h"

#include <QApplication>
#include <QCloseEvent>
#include <QEasingCurve>
#include <QHBoxLayout>
#include <QLabel>
#include <QLinearGradient>
#include <QPainter>
#include <QFont>
#include <QPainterPath>
#include <QParallelAnimationGroup>
#include <QPropertyAnimation>
#include <QRandomGenerator>
#include <QString>
#include <QTimer>
#include <QVBoxLayout>
#include <QVector>
#include <QWidget>

#ifdef Q_OS_WIN
#  include <windows.h>
#  include <dwmapi.h>
#endif

#include <string>

namespace loader {

namespace {
constexpr int kCornerRadius = 12;
constexpr int kBaseWidth    = 760;
constexpr int kBaseHeight   = 500;
constexpr int kSizeJitter   = 10;   // ± px of random size jitter applied per launch

// Random alphanumeric identifier (first char always a letter). Used to give the
// loader window a non-constant Win32 title each launch so a scanner can't match
// it against a fixed window-text string.
QString randomIdent(int minLen, int maxLen) {
    static const char kAlphabet[] =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    constexpr int kLetters = 52;                       // leading a-zA-Z only
    constexpr int kAll     = int(sizeof(kAlphabet)) - 1;
    QRandomGenerator* rng = QRandomGenerator::global();
    const int len = minLen + int(rng->bounded(quint32(maxLen - minLen + 1)));
    QString s;
    s.reserve(len);
    s.append(QLatin1Char(kAlphabet[rng->bounded(kLetters)]));
    for (int i = 1; i < len; ++i) {
        s.append(QLatin1Char(kAlphabet[rng->bounded(kAll)]));
    }
    return s;
}

QString fromW(const std::wstring& w) {
    return QString::fromWCharArray(w.c_str(), static_cast<int>(w.size()));
}

bool startsWithMinecraft(const std::wstring& title) {
    static const std::wstring prefix = L"Minecraft";
    if (title.size() < prefix.size()) return false;
    return title.compare(0, prefix.size(), prefix) == 0;
}

bool isMinecraft(const std::wstring& title, const std::wstring& cls) {
    // Either the title starts with "Minecraft" (in-game window such as
    // "Minecraft 1.20.1") or the LWJGL GLFW window class is in use (still
    // true before the title gets set during early startup).
    if (startsWithMinecraft(title)) return true;
    return _wcsicmp(cls.c_str(), L"GLFW30") == 0;
}
} // namespace

MainWindow::MainWindow(QWidget* parent)
        : QMainWindow(parent) {
    // Frameless + translucent so paintEvent can draw a rounded panel and
    // shape the window however we like. The custom TitleBar covers move/
    // minimize/close.
    setWindowFlags(Qt::FramelessWindowHint | Qt::Window);
    setAttribute(Qt::WA_TranslucentBackground);
    setAttribute(Qt::WA_NoSystemBackground);
    // Base size with a small random jitter (±kSizeJitter px) on each axis so the
    // window dimensions aren't a constant fingerprint. Minimum is lowered by the
    // jitter so a negative jitter isn't clamped back to the base size.
    setMinimumSize(kBaseWidth - kSizeJitter, kBaseHeight - kSizeJitter);
    QRandomGenerator* rng = QRandomGenerator::global();
    const int jitterW = int(rng->bounded(quint32(2 * kSizeJitter + 1))) - kSizeJitter;
    const int jitterH = int(rng->bounded(quint32(2 * kSizeJitter + 1))) - kSizeJitter;
    resize(kBaseWidth + jitterW, kBaseHeight + jitterH);

    styleApp();
    buildUi();

    timer_ = new QTimer(this);
    timer_->setInterval(1000);
    connect(timer_, &QTimer::timeout, this, &MainWindow::refreshNow);
    timer_->start();
    refreshNow();
}

void MainWindow::buildUi() {
#ifdef DANNYHACK_BUILD_REVISION
    const QString displayTitle = QStringLiteral("dannyhack Loader  ·  build %1")
            .arg(QString::fromLatin1(DANNYHACK_BUILD_REVISION).left(7));
#else
    const QString displayTitle = QStringLiteral("dannyhack Loader");
#endif
    // The OS-level window title (what GetWindowTextW and window scanners read) is
    // randomised on every launch so it can't be matched against a fixed string.
    // The visible custom title bar below still shows the branded name.
    setWindowTitle(displayTitle);

    auto* central = new QWidget(this);
    central->setAttribute(Qt::WA_TranslucentBackground);

    auto* root = new QVBoxLayout(central);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);

    titleBar_ = new TitleBar(central);
    titleBar_->setTitleText(displayTitle);
    root->addWidget(titleBar_);

    auto* body = new QWidget(central);
    body->setObjectName("body");
    body->setAttribute(Qt::WA_StyledBackground, false);
    auto* layout = new QVBoxLayout(body);
    layout->setContentsMargins(18, 14, 18, 14);
    layout->setSpacing(10);

    auto* title = new QLabel(QStringLiteral("寻找 Minecraft 进程"), body);
    title->setObjectName("title");

    hint_ = new QLabel(
        QStringLiteral("点击你想注入 dannyhack 的 Minecraft 进程； "
                       "进程每秒刷新一次。"),
        body);
    hint_->setObjectName("hint");
    hint_->setWordWrap(true);

    list_ = new InstanceList(body);
    connect(list_, &InstanceList::injectRequested,
            this, &MainWindow::onInjectRequested);

    status_ = new QLabel(QStringLiteral("正在寻找 Minecraft 进程…"), body);
    status_->setObjectName("status");
    status_->setWordWrap(true);

    layout->addWidget(title);
    layout->addWidget(hint_);
    layout->addWidget(list_, 1);
    layout->addWidget(status_);
    root->addWidget(body, 1);

    setCentralWidget(central);
}

void MainWindow::styleApp() {
    const auto& pal = theme::currentPalette();
    QString qss = QString::fromUtf8(R"qss(
        QWidget#body {
            background: transparent;
            color: %1;
            font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif;
            font-size: 13px;
        }
        QLabel#title {
            font-size: 18px;
            font-weight: 600;
            color: %2;
            padding: 2px 0 0 2px;
        }
        QLabel#hint {
            color: %3;
            font-size: 12px;
            padding-left: 2px;
        }
        QLabel#status {
            color: %4;
            padding: 7px 11px;
            border: 1px solid %5;
            border-radius: 7px;
            background: %6;
        }
    )qss")
        .arg(theme::hex(pal.bodyText))
        .arg(theme::hex(pal.titleText))
        .arg(theme::hex(pal.hintText))
        .arg(theme::hex(pal.statusText))
        .arg(theme::hex(pal.statusBorder))
        .arg(theme::rgba(pal.statusBackground, pal.statusBackground.alpha()));
    qApp->setStyleSheet(qss);
}

void MainWindow::enableWin11RoundedCorners() {
#ifdef Q_OS_WIN
    // DWMWA_WINDOW_CORNER_PREFERENCE is Windows 11+. The call is harmless
    // (returns an HRESULT we ignore) on Windows 10 — DwmSetWindowAttribute
    // just rejects the unknown attribute, no crash.
    enum { DWMWA_WINDOW_CORNER_PREFERENCE_LOCAL = 33 };
    enum { DWMWCP_ROUND_LOCAL = 2 };
    HWND hwnd = reinterpret_cast<HWND>(winId());
    if (!hwnd) return;
    int pref = DWMWCP_ROUND_LOCAL;
    DwmSetWindowAttribute(hwnd,
                          DWMWA_WINDOW_CORNER_PREFERENCE_LOCAL,
                          &pref,
                          sizeof(pref));
#endif
}

void MainWindow::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);

    QRectF r = QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5);
    QPainterPath panel;
    panel.addRoundedRect(r, kCornerRadius, kCornerRadius);

    const auto& pal = theme::currentPalette();
    QLinearGradient bg(r.topLeft(), r.bottomLeft());
    bg.setColorAt(0.0, pal.windowTop);
    bg.setColorAt(1.0, pal.windowBottom);
    p.fillPath(panel, bg);

    p.setOpacity(0.16);
    QFont watermarkFont = p.font();
    watermarkFont.setFamily(QStringLiteral("Segoe UI"));
    watermarkFont.setPointSize(72);
    watermarkFont.setBold(true);
    p.setFont(watermarkFont);
    p.setPen(QColor(255, 255, 255, 48));
    p.drawText(rect().adjusted(0, 0, 0, 0), Qt::AlignCenter, QStringLiteral("dannyhack"));
    p.setOpacity(1.0);

    p.setPen(QPen(pal.windowBorder, 1));
    p.setBrush(Qt::NoBrush);
    p.drawPath(panel);
}

void MainWindow::showEvent(QShowEvent* e) {
    QMainWindow::showEvent(e);
    enableWin11RoundedCorners();
    if (!entrancePlayed_) {
        setWindowOpacity(0.0);
    }
}

void MainWindow::playEntrance() {
    entrancePlayed_ = true;
    show();
    raise();
    activateWindow();

    auto* fade = new QPropertyAnimation(this, "windowOpacity", this);
    fade->setDuration(520);
    fade->setStartValue(windowOpacity());
    fade->setEndValue(1.0);
    fade->setEasingCurve(QEasingCurve::OutCubic);

    QRect endGeo   = geometry();
    QRect startGeo = endGeo;
    startGeo.translate(0, 18);
    setGeometry(startGeo);
    auto* slide = new QPropertyAnimation(this, "geometry", this);
    slide->setDuration(560);
    slide->setStartValue(startGeo);
    slide->setEndValue(endGeo);
    slide->setEasingCurve(QEasingCurve::OutCubic);

    auto* grp = new QParallelAnimationGroup(this);
    grp->addAnimation(fade);
    grp->addAnimation(slide);
    grp->start(QAbstractAnimation::DeleteWhenStopped);
}

void MainWindow::refreshNow() {
    auto procs = list_java_processes();
    QVector<Instance> filtered;
    filtered.reserve(procs.size());
    for (const auto& jp : procs) {
        if (!isMinecraft(jp.window_title, jp.window_class)) continue;
        Instance item;
        item.pid = jp.pid;
        item.title = fromW(jp.window_title);
        if (item.title.isEmpty()) {
            item.title = QStringLiteral("(starting up — %1)")
                    .arg(fromW(jp.window_class));
        }
        filtered.push_back(std::move(item));
    }

    list_->setInstances(filtered);
    status_->setText(QStringLiteral("找到了 %1 个 Minecraft 进程，火速注入 dannyhack")
                     .arg(list_->count()));
}

void MainWindow::onInjectRequested(unsigned long pid, const QString& title) {
    if (injectionInFlight_) return;
    injectionInFlight_ = true;

    // Freeze the list so the user can't queue another injection while we're
    // waiting for the worker thread + animation to wrap up.
    list_->setInteractive(false);
    if (timer_) timer_->stop();

    auto* overlay = new InjectionOverlay(pid, title, this);
    connect(overlay, &InjectionOverlay::completed, this,
            [this](bool /*ok*/) { playExitThenQuit(); });
    overlay->start();
}

void MainWindow::closeEvent(QCloseEvent* e) {
    // Intercept the first close so we can play the fade-out; the fade's
    // finished handler calls qApp->quit which fires another close after
    // exiting_ is set, and we let that one through normally.
    if (exiting_) {
        QMainWindow::closeEvent(e);
        return;
    }
    e->ignore();
    playExitThenQuit();
}

void MainWindow::playExitThenQuit() {
    if (exiting_) return;
    exiting_ = true;
    if (timer_) timer_->stop();

    auto* fade = new QPropertyAnimation(this, "windowOpacity", this);
    fade->setDuration(280);
    fade->setStartValue(windowOpacity());
    fade->setEndValue(0.0);
    fade->setEasingCurve(QEasingCurve::InCubic);
    connect(fade, &QAbstractAnimation::finished,
            qApp, &QApplication::quit);
    fade->start(QAbstractAnimation::DeleteWhenStopped);
}

} // namespace loader
