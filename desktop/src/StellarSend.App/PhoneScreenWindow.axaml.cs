using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using StellarSend.Core.Companion;

namespace StellarSend.App;

public partial class PhoneScreenWindow : Window
{
    private Point _down;
    private bool _pressed;

    public PhoneScreenWindow()
    {
        InitializeComponent();
        PointerPressed += OnPointerPressed;
        PointerMoved += OnPointerMoved;
        PointerReleased += OnPointerReleased;
        PointerWheelChanged += OnWheel;
        KeyDown += OnKey;
    }

    private MainViewModel? Model => DataContext as MainViewModel;

    private void OnPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (Map(e) is not { } point)
        {
            return;
        }

        _down = new Point(point.X, point.Y);
        _pressed = true;
        Model?.EnqueuePhoneInput("down", point.X, point.Y);
    }

    private void OnPointerMoved(object? sender, PointerEventArgs e)
    {
        if (!_pressed || Map(e) is not { } point)
        {
            return;
        }

        Model?.EnqueuePhoneInput("move", point.X, point.Y);
    }

    private void OnPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (Map(e) is not { } point)
        {
            _pressed = false;
            return;
        }

        var dx = point.X - _down.X;
        var dy = point.Y - _down.Y;
        if (dx * dx + dy * dy < 0.0004)
        {
            Model?.EnqueuePhoneInput("tap", point.X, point.Y);
        }
        else
        {
            Model?.EnqueuePhoneInput("swipe", _down.X, _down.Y, point.X, point.Y);
        }

        Model?.EnqueuePhoneInput("up", point.X, point.Y);
        _pressed = false;
    }

    private void OnWheel(object? sender, PointerWheelEventArgs e)
    {
        if (Map(e) is not { } point)
        {
            return;
        }

        Model?.EnqueuePhoneInput("scroll", point.X, point.Y, dy: e.Delta.Y);
    }

    private void OnKey(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Back || e.Key == Key.Escape || e.Key == Key.Enter || e.Key == Key.Tab)
        {
            Model?.EnqueuePhoneKey(e.Key.ToString());
            e.Handled = true;
            return;
        }

        if (!string.IsNullOrEmpty(e.KeySymbol))
        {
            Model?.EnqueuePhoneText(e.KeySymbol);
            e.Handled = true;
        }
    }

    private (double X, double Y)? Map(PointerEventArgs e)
    {
        if (PhoneImage.Source is not Avalonia.Media.IImage image)
        {
            return null;
        }

        var pos = e.GetPosition(PhoneImage);
        return RemoteCoords.FromView(
            pos.X,
            pos.Y,
            PhoneImage.Bounds.Width,
            PhoneImage.Bounds.Height,
            image.Size.Width,
            image.Size.Height);
    }
}
