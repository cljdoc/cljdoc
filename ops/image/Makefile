nomad-image-id: nomad.packer.json
	packer validate $^
	packer build $^ 2>&1 | tee packer-out
	cat packer-out | tail -n1 | sed 's/.*(ID: \([0-9]*\)).*/\1/' | tr -d '\n' | tee $@
