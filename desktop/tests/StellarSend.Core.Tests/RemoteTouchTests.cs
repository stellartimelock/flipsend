using StellarSend.Core.Companion;

namespace StellarSend.Core.Tests;

public sealed class RemoteTouchTests
{
    [Fact]
    public void OneFingerDragMovesOrPansAndDoesNotHoldTheButton()
    {
        var touch = new RemoteTouch();
        touch.Down(10, 20, 0);
        Assert.Empty(touch.Move(16, 22, 1, 1, 40));
        var dragged = touch.Move(80, 20, 1, 1, 80);
        Assert.Equal(["move"], dragged.Select(step => step.Kind).ToArray());
        var eased = 10 + (80 - 10) * CursorGlide.Alpha;
        Assert.Equal(eased, dragged[0].X, 3);
        Assert.True(dragged[0].X < 80);
        Assert.DoesNotContain(dragged, step => RemotePointer.PressesButton(step.Kind));
        var lifted = touch.Up(90, 24, 140);
        Assert.Equal(["move"], lifted.Select(step => step.Kind).ToArray());
        Assert.Equal(90, lifted[0].X, 3);
        Assert.Equal("idle", touch.Phase);

        var tap = new RemoteTouch();
        tap.Down(10, 20, 0);
        var clicked = tap.Up(14, 22, 100);
        Assert.Equal("tap", Assert.Single(clicked).Kind);
        Assert.Equal(10, clicked[0].X, 3);

        var zoomed = new RemoteTouch();
        zoomed.Down(0, 0, 0);
        var pan = zoomed.Move(40, 10, 1, 2, 50);
        Assert.Equal("pan", Assert.Single(pan).Kind);
        Assert.Equal(40, pan[0].Dx, 3);
        Assert.Equal(10, pan[0].Dy, 3);
        Assert.Empty(zoomed.Up(50, 10, 80, 2));
    }

    [Fact]
    public void DragSelectNeedsALongPressOrASidewaysTwoFingerDrag()
    {
        var held = new RemoteTouch();
        held.Down(10, 20, 0);
        Assert.Empty(held.Hold(RemotePointer.LongPressMs));
        var right = held.Up(12, 20, RemotePointer.LongPressMs + 40);
        Assert.Equal("longpress", Assert.Single(right).Kind);

        var select = new RemoteTouch();
        select.Down(10, 20, 0);
        select.Hold(RemotePointer.LongPressMs);
        var dragging = select.Move(80, 20, 1, 1, RemotePointer.LongPressMs + 20);
        Assert.Equal(["down", "move"], dragging.Select(step => step.Kind).ToArray());
        Assert.Equal(10, dragging[0].X, 3);
        Assert.True(dragging[1].X > 10 && dragging[1].X < 80);
        Assert.Equal("up", Assert.Single(select.Up(90, 20, RemotePointer.LongPressMs + 80)).Kind);

        var dropped = new RemoteTouch();
        dropped.Down(0, 0, 0);
        dropped.Hold(RemotePointer.LongPressMs);
        dropped.Move(60, 0, 1, 1, RemotePointer.LongPressMs + 10);
        Assert.Equal("up", Assert.Single(dropped.Cancel()).Kind);

        var scroll = new RemoteTouch();
        scroll.Down(100, 100, 0);
        scroll.ExtraFinger(100, 100);
        var wheel = scroll.Move(100, 160, 2, 1, 40);
        Assert.Equal("scroll", Assert.Single(wheel).Kind);
        Assert.True(wheel[0].Dy < 0);
        Assert.DoesNotContain(wheel, step => RemotePointer.PressesButton(step.Kind));

        var sideways = new RemoteTouch();
        sideways.Down(100, 100, 0);
        sideways.ExtraFinger(100, 100);
        var grabbed = sideways.Move(160, 100, 2, 1, 40);
        Assert.Equal(["down", "move"], grabbed.Select(step => step.Kind).ToArray());
        Assert.Equal("up", Assert.Single(sideways.PointerUp(1, 170, 100)).Kind);

        var pinch = new RemoteTouch();
        pinch.Down(0, 0, 0);
        pinch.Move(50, 0, 1, 1, 30);
        Assert.DoesNotContain(pinch.Pinch(), step => RemotePointer.PressesButton(step.Kind));
        Assert.Empty(pinch.Move(80, 10, 2, 2, 50));
        Assert.Empty(pinch.Up(80, 10, 80, 2));
    }

    [Fact]
    public void AMoveNeverPressesTheMouseAndZoomedPanMovesThePicture()
    {
        Assert.Empty(MouseChord.Buttons("move"));
        Assert.False(MouseChord.PressesOnMove("move"));
        Assert.Contains(MouseChord.LeftDown, MouseChord.Buttons("down"));
        Assert.Contains(MouseChord.LeftDown, MouseChord.Buttons("tap"));
        Assert.DoesNotContain(MouseChord.LeftDown, MouseChord.Buttons("scroll"));
        Assert.Equal("none", RemotePointer.Button("move"));

        var fit = new RemoteViewport();
        fit.PanBy(40, 10, 400, 200, 100, 200);
        Assert.Equal(0, fit.PanX, 3);
        Assert.Equal(0, fit.PanY, 3);
        var view = new RemoteViewport();
        view.Pinch(2, 200, 100, 400, 200, 100, 200);
        var before = view.Point(200, 100, 400, 200, 100, 200);
        view.PanBy(30, 0, 400, 200, 100, 200);
        var after = view.Point(200, 100, 400, 200, 100, 200);
        Assert.True(after!.Value.X < before!.Value.X);
    }
}
