// Companion to OrderService.cs, covering the declaration shapes whose names are easy to get
// wrong: a file-scoped namespace (the default since C# 10), a nested namespace, nested types,
// and a record. Every method here must come back with its full dotted path.
namespace Example.Modern;

public record Circle(double Radius)
{
    public double Area() => System.Math.PI * Radius * Radius;
}

public class Widget
{
    public int Spin(string name) => name.Length;

    public class Cog
    {
        public int Turn(int times) => times;
    }
}
