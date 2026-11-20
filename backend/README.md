# Backend — Monitor de ruido

Ver el README de la raíz del repositorio para la descripción completa.

## Levantar
    copy .env.example .env
    docker compose up -d
    cd api
    gradlew.bat run

## Verificar
    docker compose ps                                   (ruido-db healthy y ruido-mqtt up)
    docker compose exec db psql -U ruido -d ruido -c "\dt"
    docker compose exec mosquitto mosquitto_sub -t "ruido/v1/#" -v

## Apagar
    Ctrl+C en la ventana de gradlew
    docker compose stop          # conserva los datos
    docker compose down -v       # borra también la base (empezar de cero)

## Problema conocido en Windows
Si `docker compose up` dice que el puerto 15432 no está disponible, Windows lo reservó para WSL/Hyper-V.
En un cmd como administrador:
    net stop winnat
    netsh int ipv4 add excludedportrange protocol=tcp startport=15432 numberofports=1
    net start winnat
