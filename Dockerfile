# Build stage
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

# Ensure the exact SDK requested by global.json is installed
# (global.json requests 8.0.123) so install it here alongside the
# SDK image to satisfy SDK resolution inside the container.
RUN apt-get update \
	&& apt-get install -y --no-install-recommends curl ca-certificates \
	&& rm -rf /var/lib/apt/lists/*

RUN curl -sSL https://dot.net/v1/dotnet-install.sh -o dotnet-install.sh \
	&& chmod +x dotnet-install.sh \
	&& ./dotnet-install.sh --version 8.0.123 --install-dir /usr/share/dotnet --architecture x64 \
	&& rm dotnet-install.sh

ENV DOTNET_ROOT=/usr/share/dotnet
ENV PATH="$PATH:/usr/share/dotnet"

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
