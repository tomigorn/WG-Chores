# Build stage
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

# Copy csproj and restore dependencies
COPY ["WG-Chores/WG-Chores.csproj", "WG-Chores/"]
RUN dotnet restore "WG-Chores/WG-Chores.csproj"

# Copy everything else and build
COPY . .
WORKDIR "/src/WG-Chores"
RUN dotnet build "WG-Chores.csproj" -c Release -o /app/build

# Publish stage
FROM build AS publish
RUN dotnet publish "WG-Chores.csproj" -c Release -o /app/publish /p:UseAppHost=false

# Runtime stage
FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS final
WORKDIR /app

# Copy published files
COPY --from=publish /app/publish .

# Create directory for database
RUN mkdir -p /app/data

# Set environment variables for production
ENV ASPNETCORE_ENVIRONMENT=Production
ENV ASPNETCORE_URLS=http://+:8080
ENV ASPNETCORE_HTTP_PORTS=8080

EXPOSE 8080

ENTRYPOINT ["dotnet", "WG-Chores.dll"]
