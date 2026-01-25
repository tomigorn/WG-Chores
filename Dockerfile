# Multi-stage Dockerfile for WG-Chores (ASP.NET Core / Blazor server)
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

# Copy csproj and restore early for layer caching
COPY WG-Chores/WG-Chores.csproj WG-Chores/
RUN dotnet restore WG-Chores/WG-Chores.csproj

# Copy everything and publish
COPY . .
WORKDIR /src/WG-Chores
RUN dotnet publish WG-Chores.csproj -c Release -o /app/publish /p:UseAppHost=false

# Runtime image
FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS runtime
WORKDIR /app
COPY --from=build /app/publish .

ENV ASPNETCORE_URLS=http://+:80
EXPOSE 80
ENTRYPOINT ["dotnet", "WG-Chores.dll"]
