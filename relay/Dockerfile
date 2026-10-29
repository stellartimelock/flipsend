FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY desktop/Directory.Build.props desktop/Directory.Build.props
COPY desktop/src/StellarSend.Core desktop/src/StellarSend.Core
COPY desktop/src/StellarSend.Relay desktop/src/StellarSend.Relay
COPY docs/privacy.html docs/privacy.html
COPY relay/tiers.json relay/tiers.json
WORKDIR /src/desktop/src/StellarSend.Relay
RUN dotnet publish -c Release -o /out

FROM mcr.microsoft.com/dotnet/runtime:10.0
WORKDIR /app
COPY --from=build /out .
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["dotnet", "StellarSend.Relay.dll"]
